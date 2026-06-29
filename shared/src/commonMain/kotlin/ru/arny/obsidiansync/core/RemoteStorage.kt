package ru.arny.obsidiansync.core

interface RemoteStorage {
    suspend fun checkConnection(): RemoteCheckResult
    suspend fun getQuota(): RemoteQuota?

    suspend fun ensureDirectory(path: RemotePath)
    suspend fun exists(path: RemotePath): Boolean

    suspend fun list(path: RemotePath): List<RemoteObject>

    suspend fun uploadBytes(
        path: RemotePath,
        bytes: ByteArray,
        overwrite: Boolean,
    ): RemoteUploadResult

    suspend fun downloadBytes(path: RemotePath): ByteArray

    suspend fun delete(path: RemotePath, permanently: Boolean = false)

    suspend fun move(
        from: RemotePath,
        to: RemotePath,
        overwrite: Boolean,
    )

    suspend fun listReadyMarkers(vaultId: VaultId): List<ReadyMarker>
    suspend fun uploadManifest(manifest: SyncManifest)
    suspend fun downloadManifest(ref: ManifestRef): SyncManifest
    suspend fun uploadBundle(bundle: EncryptedBundle)
    suspend fun downloadBundle(ref: BundleRef): ByteArray
}
