package ru.arny.obsidiansync.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@JvmInline
value class VaultId(val value: String)

@JvmInline
value class RemotePath(val value: String) {
    init {
        require(value.startsWith("disk:/") || value.startsWith("app:/")) {
            "RemotePath must start with disk:/ or app:/"
        }
    }

    fun child(relative: String): RemotePath {
        val base = value.trimEnd('/')
        val suffix = relative.trim('/').replace('\\', '/')
        return RemotePath("$base/$suffix")
    }

    override fun toString(): String = value
}

@Serializable
data class RemoteQuota(
    @SerialName("total_space") val totalSpace: Long,
    @SerialName("used_space") val usedSpace: Long,
    @SerialName("trash_size") val trashSize: Long? = null,
) {
    val freeSpace: Long get() = (totalSpace - usedSpace).coerceAtLeast(0)
}

@Serializable
data class RemoteCheckResult(
    val ok: Boolean,
    val backend: String,
    val message: String,
    val quota: RemoteQuota? = null,
)

@Serializable
data class RemoteObject(
    val path: String,
    val name: String,
    val type: RemoteObjectType,
    val size: Long? = null,
    val createdAt: String? = null,
    val modifiedAt: String? = null,
    val hash: String? = null,
)

@Serializable
enum class RemoteObjectType {
    File,
    Directory,
    Unknown,
}

@Serializable
data class RemoteUploadResult(
    val path: String,
    val size: Long,
    val overwrite: Boolean,
)

@Serializable
data class ReadyMarker(
    val bundleId: String,
    val path: String,
    val createdAt: String? = null,
)

@Serializable
data class ManifestRef(
    val bundleId: String,
    val path: String,
)

@Serializable
data class BundleRef(
    val bundleId: String,
    val path: String,
    val expectedHash: String? = null,
    val expectedSize: Long? = null,
)

@Serializable
data class EncryptedBundle(
    val bundleId: String,
    val path: String,
    val bytes: ByteArray,
    val sha256: String,
) {
    override fun equals(other: Any?): Boolean =
        other is EncryptedBundle &&
            bundleId == other.bundleId &&
            path == other.path &&
            sha256 == other.sha256 &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = bundleId.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + sha256.hashCode()
        return result
    }
}

@Serializable
data class SyncManifest(
    @SerialName("format_version") val formatVersion: Int = 2,
    val protocol: String = "obsidelta",
    @SerialName("vault_id") val vaultId: String,
    @SerialName("bundle_id") val bundleId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("created_at") val createdAt: String,
    val sequence: Int,
    val status: ManifestStatus = ManifestStatus.Ready,
    val remote: ManifestRemote,
    val bundle: ManifestBundle,
    val changes: List<ManifestChange> = emptyList(),
    val deletes: List<ManifestDelete> = emptyList(),
    val renames: List<ManifestRename> = emptyList(),
    val snapshot: List<VaultFileSnapshot> = emptyList(),
    val checkpoint: Boolean = false,
    val stats: ManifestStats = ManifestStats(),
)

@Serializable
enum class ManifestStatus {
    @SerialName("draft") Draft,
    @SerialName("ready") Ready,
}

@Serializable
data class ManifestRemote(
    val backend: String,
    val root: String,
    @SerialName("manifest_path") val manifestPath: String,
    @SerialName("bundle_path") val bundlePath: String,
    @SerialName("ready_marker_path") val readyMarkerPath: String,
)

@Serializable
data class ManifestBundle(
    @SerialName("file_name") val fileName: String,
    val size: Long,
    @SerialName("hash_algorithm") val hashAlgorithm: String = "sha256",
    val hash: String,
    val archive: String = "zip",
    val encrypted: Boolean,
    val encryption: ManifestEncryption? = null,
)

@Serializable
data class ManifestEncryption(
    val algorithm: String,
    @SerialName("key_id") val keyId: String,
)

@Serializable
data class ManifestChange(
    val path: String,
    val operation: ManifestOperation,
    @SerialName("base_hash") val baseHash: String? = null,
    @SerialName("new_hash") val newHash: String,
    val size: Long,
    @SerialName("modified_at") val modifiedAt: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("file_kind") val fileKind: FileKind = FileKind.Text,
)

@Serializable
data class ManifestDelete(
    val path: String,
    val operation: ManifestOperation = ManifestOperation.Delete,
    @SerialName("base_hash") val baseHash: String,
    @SerialName("deleted_at") val deletedAt: String,
    @SerialName("file_kind") val fileKind: FileKind = FileKind.Text,
)

@Serializable
data class ManifestRename(
    @SerialName("from_path") val fromPath: String,
    @SerialName("to_path") val toPath: String,
    @SerialName("base_hash") val baseHash: String? = null,
    @SerialName("new_hash") val newHash: String? = null,
)

@Serializable
enum class ManifestOperation {
    @SerialName("create") Create,
    @SerialName("modify") Modify,
    @SerialName("delete") Delete,
}

@Serializable
enum class FileKind {
    @SerialName("text") Text,
    @SerialName("media") Media,
    @SerialName("binary") Binary,
    @SerialName("settings") Settings,
    @SerialName("unknown") Unknown,
}

@Serializable
data class ManifestStats(
    val created: Int = 0,
    val modified: Int = 0,
    val deleted: Int = 0,
    @SerialName("total_files") val totalFiles: Int = created + modified + deleted,
    @SerialName("total_uncompressed_size") val totalUncompressedSize: Long = 0,
)

sealed class RemoteStorageError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Unauthorized(message: String = "Unauthorized") : RemoteStorageError(message)
    class Forbidden(message: String = "Forbidden") : RemoteStorageError(message)
    class NotFound(message: String = "Not found") : RemoteStorageError(message)
    class AlreadyExists(message: String = "Already exists") : RemoteStorageError(message)
    class QuotaExceeded(message: String = "Quota exceeded") : RemoteStorageError(message)
    class NetworkError(message: String, cause: Throwable? = null) : RemoteStorageError(message, cause)
    class RateLimited(message: String = "Rate limited") : RemoteStorageError(message)
    class ServerError(message: String) : RemoteStorageError(message)
    class HashMismatch(message: String) : RemoteStorageError(message)
    class UnexpectedResponse(message: String) : RemoteStorageError(message)
}
