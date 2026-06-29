package ru.arny.obsidiansync.remote.yandex

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.arny.obsidiansync.core.BundleRef
import ru.arny.obsidiansync.core.EncryptedBundle
import ru.arny.obsidiansync.core.ManifestRef
import ru.arny.obsidiansync.core.ObsideltaJson
import ru.arny.obsidiansync.core.ReadyMarker
import ru.arny.obsidiansync.core.RemoteCheckResult
import ru.arny.obsidiansync.core.RemoteLayout
import ru.arny.obsidiansync.core.RemoteObject
import ru.arny.obsidiansync.core.RemoteObjectType
import ru.arny.obsidiansync.core.RemotePath
import ru.arny.obsidiansync.core.RemoteQuota
import ru.arny.obsidiansync.core.RemoteStorage
import ru.arny.obsidiansync.core.RemoteStorageError
import ru.arny.obsidiansync.core.RemoteUploadResult
import ru.arny.obsidiansync.core.SyncManifest
import ru.arny.obsidiansync.core.VaultId
import ru.arny.obsidiansync.core.toJson
import ru.arny.obsidiansync.core.verifySha256

class YandexDiskRestRemoteStorage(
    private val httpClient: HttpClient,
    private val oauthToken: String,
    private val root: RemotePath,
    private val apiBase: String = DEFAULT_API_BASE,
) : RemoteStorage {
    private val layout = RemoteLayout(root)

    override suspend fun checkConnection(): RemoteCheckResult = runCatching {
        val quota = getQuota()
        RemoteCheckResult(
            ok = true,
            backend = BACKEND_ID,
            message = "Yandex Disk REST API connection is available.",
            quota = quota,
        )
    }.getOrElse { error ->
        RemoteCheckResult(
            ok = false,
            backend = BACKEND_ID,
            message = error.message ?: "Yandex Disk REST API connection failed.",
            quota = null,
        )
    }

    override suspend fun getQuota(): RemoteQuota? {
        val response = authorizedGet("$apiBase/")
        ensureSuccess(response)
        val dto = ObsideltaJson.decodeFromString<DiskInfoDto>(response.bodyAsText())
        val total = dto.totalSpace ?: return null
        val used = dto.usedSpace ?: return null
        return RemoteQuota(totalSpace = total, usedSpace = used, trashSize = dto.trashSize)
    }

    override suspend fun ensureDirectory(path: RemotePath) {
        val response = httpClient.put("$apiBase/resources") {
            yandexOAuth()
            parameter("path", path.value)
        }
        if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.Conflict) return
        ensureSuccess(response)
    }

    override suspend fun exists(path: RemotePath): Boolean {
        val response = authorizedGet("$apiBase/resources") {
            parameter("path", path.value)
            parameter("limit", 1)
        }
        if (response.status == HttpStatusCode.NotFound) return false
        ensureSuccess(response)
        return true
    }

    override suspend fun list(path: RemotePath): List<RemoteObject> {
        val response = authorizedGet("$apiBase/resources") {
            parameter("path", path.value)
            parameter("limit", 10_000)
        }
        ensureSuccess(response)
        val dto = ObsideltaJson.decodeFromString<ResourceDto>(response.bodyAsText())
        return dto.embedded?.items.orEmpty().map { it.toRemoteObject() }
    }

    override suspend fun uploadBytes(
        path: RemotePath,
        bytes: ByteArray,
        overwrite: Boolean,
    ): RemoteUploadResult {
        val linkResponse = authorizedGet("$apiBase/resources/upload") {
            parameter("path", path.value)
            parameter("overwrite", overwrite)
        }
        ensureSuccess(linkResponse)
        val link = ObsideltaJson.decodeFromString<LinkDto>(linkResponse.bodyAsText())
        val uploadResponse = httpClient.put(link.href) {
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        ensureSuccess(uploadResponse)

        val metadata = getResource(path)
        if (metadata.size != bytes.size.toLong()) {
            throw RemoteStorageError.UnexpectedResponse(
                "Uploaded size mismatch for ${path.value}: remote=${metadata.size}, local=${bytes.size}",
            )
        }
        return RemoteUploadResult(path = path.value, size = bytes.size.toLong(), overwrite = overwrite)
    }

    override suspend fun downloadBytes(path: RemotePath): ByteArray {
        val linkResponse = authorizedGet("$apiBase/resources/download") {
            parameter("path", path.value)
        }
        ensureSuccess(linkResponse)
        val link = ObsideltaJson.decodeFromString<LinkDto>(linkResponse.bodyAsText())
        val downloadResponse = httpClient.get(link.href)
        ensureSuccess(downloadResponse)
        return downloadResponse.bodyAsBytes()
    }

    override suspend fun delete(path: RemotePath, permanently: Boolean) {
        val response = httpClient.delete("$apiBase/resources") {
            yandexOAuth()
            parameter("path", path.value)
            parameter("permanently", permanently)
        }
        if (response.status == HttpStatusCode.NotFound) return
        ensureSuccess(response)
    }

    override suspend fun move(from: RemotePath, to: RemotePath, overwrite: Boolean) {
        val response = httpClient.post("$apiBase/resources/move") {
            yandexOAuth()
            parameter("from", from.value)
            parameter("path", to.value)
            parameter("overwrite", overwrite)
        }
        ensureSuccess(response)
    }

    override suspend fun listReadyMarkers(vaultId: VaultId): List<ReadyMarker> {
        val readyPath = layout.ready(vaultId)
        if (!exists(readyPath)) return emptyList()
        return list(readyPath)
            .filter { it.type == RemoteObjectType.File && it.name.endsWith(".ready") }
            .map { remoteObject ->
                ReadyMarker(
                    bundleId = remoteObject.name.removeSuffix(".ready"),
                    path = remoteObject.path,
                    createdAt = remoteObject.createdAt,
                )
            }
    }

    override suspend fun uploadManifest(manifest: SyncManifest) {
        uploadBytes(RemotePath(manifest.remote.manifestPath), manifest.toJson().encodeToByteArray(), overwrite = false)
    }

    override suspend fun downloadManifest(ref: ManifestRef): SyncManifest =
        ObsideltaJson.decodeFromString(downloadBytes(RemotePath(ref.path)).decodeToString())

    override suspend fun uploadBundle(bundle: EncryptedBundle) {
        if (!verifySha256(bundle.bytes, bundle.sha256)) {
            throw RemoteStorageError.HashMismatch("Local encrypted bundle hash mismatch for ${bundle.bundleId}")
        }
        uploadBytes(RemotePath(bundle.path), bundle.bytes, overwrite = false)
    }

    override suspend fun downloadBundle(ref: BundleRef): ByteArray {
        val bytes = downloadBytes(RemotePath(ref.path))
        ref.expectedSize?.let { expectedSize ->
            if (bytes.size.toLong() != expectedSize) {
                throw RemoteStorageError.UnexpectedResponse(
                    "Downloaded bundle size mismatch for ${ref.bundleId}: remote=${bytes.size}, expected=$expectedSize",
                )
            }
        }
        ref.expectedHash?.let { expectedHash ->
            if (!verifySha256(bytes, expectedHash)) {
                throw RemoteStorageError.HashMismatch("Downloaded bundle hash mismatch for ${ref.bundleId}")
            }
        }
        return bytes
    }

    suspend fun getResource(path: RemotePath): RemoteObject {
        val response = authorizedGet("$apiBase/resources") {
            parameter("path", path.value)
        }
        ensureSuccess(response)
        return ObsideltaJson.decodeFromString<ResourceDto>(response.bodyAsText()).toRemoteObject()
    }

    suspend fun publishReadyMarker(vaultId: VaultId, bundleId: String) {
        val marker = layout.readyMarkerPath(vaultId, bundleId)
        ensureDirectory(layout.ready(vaultId))
        uploadBytes(marker, ByteArray(0), overwrite = false)
    }

    private suspend fun authorizedGet(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = httpClient.get(url) {
        yandexOAuth()
        header(HttpHeaders.Accept, ContentType.Application.Json)
        block()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.yandexOAuth() {
        header(HttpHeaders.Authorization, "OAuth $oauthToken")
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val body = response.bodyAsText()
        throw when {
            response.status == HttpStatusCode.Unauthorized -> RemoteStorageError.Unauthorized(body.ifBlank { "Unauthorized" })
            response.status == HttpStatusCode.Forbidden -> RemoteStorageError.Forbidden(body.ifBlank { "Forbidden" })
            response.status == HttpStatusCode.NotFound -> RemoteStorageError.NotFound(body.ifBlank { "Not found" })
            response.status == HttpStatusCode.Conflict -> RemoteStorageError.AlreadyExists(body.ifBlank { "Already exists" })
            response.status.value == 507 -> RemoteStorageError.QuotaExceeded(body.ifBlank { "Quota exceeded" })
            response.status.value == 429 -> RemoteStorageError.RateLimited(body.ifBlank { "Rate limited" })
            response.status.value >= 500 -> {
                RemoteStorageError.ServerError(body.ifBlank { "Server error: ${response.status.value}" })
            }
            else -> {
                RemoteStorageError.UnexpectedResponse(body.ifBlank { "Unexpected response: ${response.status.value}" })
            }
        }
    }

    private fun ResourceDto.toRemoteObject(): RemoteObject = RemoteObject(
        path = path.orEmpty(),
        name = name ?: path.orEmpty().substringAfterLast('/'),
        type = when (type) {
            "file" -> RemoteObjectType.File
            "dir" -> RemoteObjectType.Directory
            else -> RemoteObjectType.Unknown
        },
        size = size,
        createdAt = created,
        modifiedAt = modified,
        hash = sha256 ?: md5,
    )

    companion object {
        const val BACKEND_ID = "yandex_disk_rest"
        const val DEFAULT_API_BASE = "https://cloud-api.yandex.net/v1/disk"
    }
}

@Serializable
private data class DiskInfoDto(
    @SerialName("total_space") val totalSpace: Long? = null,
    @SerialName("used_space") val usedSpace: Long? = null,
    @SerialName("trash_size") val trashSize: Long? = null,
)

@Serializable
private data class LinkDto(
    val href: String,
    val method: String? = null,
    val templated: Boolean? = null,
)

@Serializable
private data class ResourceDto(
    val name: String? = null,
    val path: String? = null,
    val type: String? = null,
    val size: Long? = null,
    val created: String? = null,
    val modified: String? = null,
    val md5: String? = null,
    val sha256: String? = null,
    @SerialName("_embedded") val embedded: EmbeddedDto? = null,
)

@Serializable
private data class EmbeddedDto(
    val items: List<ResourceDto> = emptyList(),
)
