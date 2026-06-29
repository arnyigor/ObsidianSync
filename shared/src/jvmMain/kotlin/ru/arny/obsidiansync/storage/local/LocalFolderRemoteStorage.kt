package ru.arny.obsidiansync.storage.local

import ru.arny.obsidiansync.core.BundleRef
import ru.arny.obsidiansync.core.EncryptedBundle
import ru.arny.obsidiansync.core.ManifestRef
import ru.arny.obsidiansync.core.ObsideltaJson
import ru.arny.obsidiansync.core.ReadyMarker
import ru.arny.obsidiansync.core.RemoteCheckResult
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class LocalFolderRemoteStorage(
    private val rootDirectory: Path,
    private val remoteRoot: RemotePath = RemotePath("disk:/LocalFolderRemote"),
) : RemoteStorage {
    override suspend fun checkConnection(): RemoteCheckResult {
        rootDirectory.createDirectories()
        return RemoteCheckResult(
            ok = true,
            backend = BACKEND_ID,
            message = "Local folder remote is available: $rootDirectory",
        )
    }

    override suspend fun getQuota(): RemoteQuota? = null

    override suspend fun ensureDirectory(path: RemotePath) {
        resolve(path).createDirectories()
    }

    override suspend fun exists(path: RemotePath): Boolean = resolve(path).exists()

    override suspend fun list(path: RemotePath): List<RemoteObject> {
        val directory = resolve(path)
        if (!directory.exists()) throw RemoteStorageError.NotFound(path.value)
        if (!directory.isDirectory()) throw RemoteStorageError.UnexpectedResponse("Not a directory: ${path.value}")
        return Files.list(directory).use { stream ->
            stream.sorted().map { child -> child.toRemoteObject(path.value.trimEnd('/') + "/" + child.name) }.toList()
        }
    }

    override suspend fun uploadBytes(path: RemotePath, bytes: ByteArray, overwrite: Boolean): RemoteUploadResult {
        val target = resolve(path)
        if (target.exists() && !overwrite) throw RemoteStorageError.AlreadyExists(path.value)
        target.parent?.createDirectories()
        target.writeBytes(bytes)
        return RemoteUploadResult(path = path.value, size = bytes.size.toLong(), overwrite = overwrite)
    }

    override suspend fun downloadBytes(path: RemotePath): ByteArray {
        val target = resolve(path)
        if (!target.exists()) throw RemoteStorageError.NotFound(path.value)
        return target.readBytes()
    }

    override suspend fun delete(path: RemotePath, permanently: Boolean) {
        resolve(path).deleteIfExists()
    }

    override suspend fun move(from: RemotePath, to: RemotePath, overwrite: Boolean) {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.exists()) throw RemoteStorageError.NotFound(from.value)
        if (target.exists() && !overwrite) throw RemoteStorageError.AlreadyExists(to.value)
        target.parent?.createDirectories()
        if (overwrite) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(source, target)
    }

    override suspend fun listReadyMarkers(vaultId: VaultId): List<ReadyMarker> {
        val readyPath = remoteRoot.child("vaults/${vaultId.value}/ready")
        if (!exists(readyPath)) return emptyList()
        return list(readyPath)
            .filter { it.type == RemoteObjectType.File && it.name.endsWith(".ready") }
            .map { ReadyMarker(bundleId = it.name.removeSuffix(".ready"), path = it.path, createdAt = it.createdAt) }
    }

    override suspend fun uploadManifest(manifest: SyncManifest) {
        uploadBytes(RemotePath(manifest.remote.manifestPath), manifest.toJson().encodeToByteArray(), overwrite = false)
    }

    override suspend fun downloadManifest(ref: ManifestRef): SyncManifest =
        ObsideltaJson.decodeFromString(downloadBytes(RemotePath(ref.path)).decodeToString())

    override suspend fun uploadBundle(bundle: EncryptedBundle) {
        if (!verifySha256(bundle.bytes, bundle.sha256)) {
            throw RemoteStorageError.HashMismatch("Local bundle hash mismatch for ${bundle.bundleId}")
        }
        uploadBytes(RemotePath(bundle.path), bundle.bytes, overwrite = false)
    }

    override suspend fun downloadBundle(ref: BundleRef): ByteArray {
        val bytes = downloadBytes(RemotePath(ref.path))
        ref.expectedSize?.let { expected ->
            if (bytes.size.toLong() != expected) throw RemoteStorageError.UnexpectedResponse("Size mismatch for ${ref.bundleId}")
        }
        ref.expectedHash?.let { expected ->
            if (!verifySha256(bytes, expected)) throw RemoteStorageError.HashMismatch("Hash mismatch for ${ref.bundleId}")
        }
        return bytes
    }

    private fun resolve(path: RemotePath): Path {
        val relative = path.value
            .removePrefix(remoteRoot.value)
            .trim('/')
            .replace(':', '_')
        return if (relative.isBlank()) rootDirectory else rootDirectory.resolve(relative)
    }

    private fun Path.toRemoteObject(remotePath: String): RemoteObject = RemoteObject(
        path = remotePath,
        name = name,
        type = if (isDirectory()) RemoteObjectType.Directory else RemoteObjectType.File,
        size = if (isDirectory()) null else fileSize(),
    )

    companion object {
        const val BACKEND_ID = "local_folder"
    }
}
