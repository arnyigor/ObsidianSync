package ru.arny.obsidiansync.core

class BundlePublisher(
    private val remoteStorage: RemoteStorage,
    private val remoteRoot: RemotePath,
) {
    private val layout = RemoteLayout(remoteRoot)

    suspend fun publish(
        vaultId: VaultId,
        manifest: SyncManifest,
        bundle: EncryptedBundle,
        onProgress: SyncProgressReporter = {},
    ): PublishResult {
        if (manifest.bundleId != bundle.bundleId) {
            throw RemoteStorageError.UnexpectedResponse("Manifest and bundle ids differ.")
        }
        if (!verifySha256(bundle.bytes, manifest.bundle.hash)) {
            throw RemoteStorageError.HashMismatch("Bundle hash does not match manifest for ${bundle.bundleId}.")
        }

        val readyMarker = layout.readyMarkerPath(vaultId, bundle.bundleId)
        if (remoteStorage.exists(readyMarker)) {
            throw RemoteStorageError.AlreadyExists("Bundle ${bundle.bundleId} is already published.")
        }

        val tmpBundle = layout.tmpBundlePath(vaultId, bundle.bundleId)
        val tmpManifest = layout.tmpManifestPath(vaultId, bundle.bundleId)
        val finalBundle = RemotePath(manifest.remote.bundlePath)
        val finalManifest = RemotePath(manifest.remote.manifestPath)

        onProgress.report(SyncStage.Publishing, "Подготавливаю папки в облаке…")
        ensureParentDirectories(vaultId, manifest.createdAt)
        onProgress.report(
            SyncStage.UploadingBundle,
            "Загружаю snapshot (${bundle.bytes.size / 1024} КБ)…",
        )
        remoteStorage.uploadBytes(tmpBundle, bundle.bytes, overwrite = false)
        onProgress.report(SyncStage.UploadingManifest, "Загружаю manifest…")
        remoteStorage.uploadBytes(tmpManifest, manifest.toJson().encodeToByteArray(), overwrite = false)

        onProgress.report(SyncStage.Publishing, "Публикую пакет и ready-marker…")
        remoteStorage.move(tmpBundle, finalBundle, overwrite = false)
        remoteStorage.move(tmpManifest, finalManifest, overwrite = false)
        remoteStorage.uploadBytes(readyMarker, ByteArray(0), overwrite = false)

        return PublishResult(
            bundleId = bundle.bundleId,
            manifestPath = finalManifest.value,
            bundlePath = finalBundle.value,
            readyMarkerPath = readyMarker.value,
        )
    }

    private suspend fun ensureParentDirectories(vaultId: VaultId, createdAt: String) {
        val vaultRoot = layout.vaultRoot(vaultId)
        remoteStorage.ensureDirectory(remoteRoot)
        remoteStorage.ensureDirectory(remoteRoot.child("vaults"))
        remoteStorage.ensureDirectory(vaultRoot)
        remoteStorage.ensureDirectory(layout.meta(vaultId))
        remoteStorage.ensureDirectory(layout.devices(vaultId))
        remoteStorage.ensureDirectory(layout.ready(vaultId))
        remoteStorage.ensureDirectory(vaultRoot.child("tmp"))
        remoteStorage.ensureDirectory(layout.tmpUploads(vaultId))
        remoteStorage.ensureDirectory(vaultRoot.child("manifests"))
        remoteStorage.ensureDirectory(vaultRoot.child("bundles"))

        val year = createdAt.take(4)
        val month = createdAt.drop(5).take(2)
        remoteStorage.ensureDirectory(vaultRoot.child("manifests/$year"))
        remoteStorage.ensureDirectory(vaultRoot.child("manifests/$year/$month"))
        remoteStorage.ensureDirectory(vaultRoot.child("bundles/$year"))
        remoteStorage.ensureDirectory(vaultRoot.child("bundles/$year/$month"))
    }
}

data class PublishResult(
    val bundleId: String,
    val manifestPath: String,
    val bundlePath: String,
    val readyMarkerPath: String,
)
