package ru.arny.obsidiansync.core

data class SnapshotPackage(
    val bytes: ByteArray,
    val files: List<ManifestChange>,
    val deletes: List<ManifestDelete>,
    val snapshot: List<VaultFileSnapshot>,
    val checkpoint: Boolean,
)

data class ApplyBundleResult(
    val appliedFiles: Int,
    val conflicts: Int,
)

data class ManualSyncResult(
    val downloadedFiles: Int,
    val uploadedFiles: Int,
    val conflicts: Int,
    val remoteBundles: Int,
    val publishedBundleId: String,
    val published: Boolean,
    val checkpoint: Boolean,
    val compactedPackages: Int,
)

class ManualSyncCoordinator(
    private val remoteStorage: RemoteStorage,
    private val remoteRoot: RemotePath,
    private val vaultId: VaultId,
    private val deviceId: String,
    private val deviceName: String,
) {
    private val layout = RemoteLayout(remoteRoot)

    suspend fun findRemote(): Pair<List<ReadyMarker>, SyncManifest?> {
        val markers = remoteStorage.listReadyMarkers(vaultId)
            .sortedBy { timestampFromBundleId(it.bundleId) }
        val latest = markers.lastOrNull()?.let { marker ->
            remoteStorage.downloadManifest(
                ManifestRef(
                    bundleId = marker.bundleId,
                    path = layout.manifestPath(vaultId, marker.bundleId, createdAtFromBundleId(marker.bundleId)).value,
                ),
            )
        }
        return markers to latest
    }

    suspend fun synchronize(
        createdAt: String,
        sequence: Int,
        currentSnapshot: List<VaultFileSnapshot>,
        isApplied: suspend (String) -> Boolean,
        applyBundle: suspend (SyncManifest, ByteArray, SyncProgressReporter) -> ApplyBundleResult,
        buildSnapshot: suspend (String, String, List<VaultFileSnapshot>, Boolean, SyncProgressReporter) -> SnapshotPackage,
        markApplied: suspend (String) -> Unit,
        onProgress: SyncProgressReporter = {},
    ): ManualSyncResult {
        onProgress.report(SyncStage.CheckingRemote, "Проверяю последний пакет в облаке…")
        val (markers, latestManifest) = findRemote()
        val createCheckpoint = markers.isEmpty() || markers.size >= MAX_REMOTE_PACKAGES
        val alreadyMatchesLatest = latestManifest != null && diffVaultSnapshots(
            previous = latestManifest.fileSnapshots(),
            current = currentSnapshot,
        ).allChanges.isEmpty()
        if (alreadyMatchesLatest) {
            markers.forEach { markApplied(it.bundleId) }
            if (!createCheckpoint) {
                return ManualSyncResult(
                    downloadedFiles = 0,
                    uploadedFiles = 0,
                    conflicts = 0,
                    remoteBundles = markers.size,
                    publishedBundleId = latestManifest.bundleId,
                    published = false,
                    checkpoint = false,
                    compactedPackages = 0,
                )
            }
        }
        val manifestsById = mutableMapOf<String, SyncManifest>()
        if (latestManifest != null) manifestsById[latestManifest.bundleId] = latestManifest
        var downloadedFiles = 0
        var conflicts = 0
        val pendingMarkers = mutableListOf<ReadyMarker>()
        for (marker in markers) {
            if (!isApplied(marker.bundleId)) pendingMarkers += marker
        }

        pendingMarkers.forEachIndexed { index, marker ->
            val manifest = if (marker.bundleId == latestManifest?.bundleId) {
                requireNotNull(latestManifest)
            } else {
                remoteStorage.downloadManifest(
                    ManifestRef(
                        bundleId = marker.bundleId,
                        path = layout.manifestPath(
                            vaultId,
                            marker.bundleId,
                            createdAtFromBundleId(marker.bundleId),
                        ).value,
                    ),
                )
            }
            manifestsById[manifest.bundleId] = manifest
            if (manifest.deviceId == deviceId) {
                markApplied(manifest.bundleId)
                return@forEachIndexed
            }

            onProgress.report(
                SyncStage.DownloadingRemote,
                "Скачиваю ${manifest.bundle.fileName} (${index + 1} из ${pendingMarkers.size})…",
                completedItems = index,
                totalItems = pendingMarkers.size,
            )
            val bundleBytes = remoteStorage.downloadBundle(
                BundleRef(
                    bundleId = manifest.bundleId,
                    path = manifest.remote.bundlePath,
                    expectedHash = manifest.bundle.hash,
                    expectedSize = manifest.bundle.size,
                ),
            )
            onProgress.report(SyncStage.ApplyingRemote, "Применяю облачные изменения…")
            val applyResult = applyBundle(manifest, bundleBytes, onProgress)
            downloadedFiles += applyResult.appliedFiles
            conflicts += applyResult.conflicts
            markApplied(manifest.bundleId)
        }

        val bundleId = buildBundleId(deviceId, sequence, createdAt)
        onProgress.report(SyncStage.BuildingSnapshot, "Сканирую локальный Vault…")
        val snapshot = buildSnapshot(
            bundleId,
            createdAt,
            latestManifest?.fileSnapshots().orEmpty(),
            createCheckpoint,
            onProgress,
        )
        require(snapshot.snapshot.isNotEmpty()) { "В Vault нет поддерживаемых файлов для синхронизации" }

        if (!snapshot.checkpoint && snapshot.files.isEmpty() && snapshot.deletes.isEmpty()) {
            return ManualSyncResult(
                downloadedFiles = downloadedFiles,
                uploadedFiles = 0,
                conflicts = conflicts,
                remoteBundles = markers.size,
                publishedBundleId = latestManifest?.bundleId.orEmpty(),
                published = false,
                checkpoint = false,
                compactedPackages = 0,
            )
        }

        val bundlePath = layout.bundlePath(vaultId, bundleId, createdAt)
        val manifestPath = layout.manifestPath(vaultId, bundleId, createdAt)
        val readyPath = layout.readyMarkerPath(vaultId, bundleId)
        val hash = sha256Hex(snapshot.bytes)
        val manifest = SyncManifest(
            vaultId = vaultId.value,
            bundleId = bundleId,
            deviceId = deviceId,
            deviceName = deviceName,
            createdAt = createdAt,
            sequence = sequence,
            remote = ManifestRemote(
                backend = "yandex_disk_rest",
                root = remoteRoot.value,
                manifestPath = manifestPath.value,
                bundlePath = bundlePath.value,
                readyMarkerPath = readyPath.value,
            ),
            bundle = ManifestBundle(
                fileName = bundlePath.value.substringAfterLast('/'),
                size = snapshot.bytes.size.toLong(),
                hash = hash,
                encrypted = false,
            ),
            changes = snapshot.files,
            deletes = snapshot.deletes,
            snapshot = snapshot.snapshot,
            checkpoint = snapshot.checkpoint,
            stats = ManifestStats(
                created = snapshot.files.count { it.operation == ManifestOperation.Create },
                modified = snapshot.files.count { it.operation == ManifestOperation.Modify },
                deleted = snapshot.deletes.size,
                totalFiles = snapshot.files.size + snapshot.deletes.size,
                totalUncompressedSize = snapshot.files.sumOf { it.size },
            ),
        )
        BundlePublisher(remoteStorage, remoteRoot).publish(
            vaultId = vaultId,
            manifest = manifest,
            bundle = EncryptedBundle(
                bundleId = bundleId,
                path = bundlePath.value,
                bytes = snapshot.bytes,
                sha256 = hash,
            ),
            onProgress = onProgress,
        )
        markApplied(bundleId)

        if (snapshot.checkpoint && markers.isNotEmpty()) {
            onProgress.report(
                SyncStage.Publishing,
                "Checkpoint опубликован. Удаляю ${markers.size} старых пакетов…",
            )
            markers.forEach { marker ->
                val oldManifest = manifestsById[marker.bundleId] ?: remoteStorage.downloadManifest(
                    ManifestRef(
                        bundleId = marker.bundleId,
                        path = layout.manifestPath(
                            vaultId,
                            marker.bundleId,
                            createdAtFromBundleId(marker.bundleId),
                        ).value,
                    ),
                )
                remoteStorage.delete(RemotePath(marker.path), permanently = true)
                remoteStorage.delete(RemotePath(oldManifest.remote.bundlePath), permanently = true)
                remoteStorage.delete(RemotePath(oldManifest.remote.manifestPath), permanently = true)
            }
        }

        return ManualSyncResult(
            downloadedFiles = downloadedFiles,
            uploadedFiles = snapshot.files.size + snapshot.deletes.size,
            conflicts = conflicts,
            remoteBundles = if (snapshot.checkpoint) 1 else markers.size + 1,
            publishedBundleId = bundleId,
            published = true,
            checkpoint = snapshot.checkpoint,
            compactedPackages = if (snapshot.checkpoint) markers.size else 0,
        )
    }

    companion object {
        const val MAX_REMOTE_PACKAGES = 10
    }
}

fun createdAtFromBundleId(bundleId: String): String {
    val timestamp = timestampFromBundleId(bundleId)
    require(timestamp.length == 16) { "Некорректный bundle id: $bundleId" }
    return buildString(20) {
        append(timestamp, 0, 4)
        append('-')
        append(timestamp, 4, 6)
        append('-')
        append(timestamp, 6, 8)
        append('T')
        append(timestamp, 9, 11)
        append(':')
        append(timestamp, 11, 13)
        append(':')
        append(timestamp, 13, 15)
        append('Z')
    }
}

private fun timestampFromBundleId(bundleId: String): String {
    val match = Regex("(\\d{8}T\\d{6})(?:\\d+)?Z$").find(bundleId)
        ?: error("Bundle id не содержит UTC timestamp: $bundleId")
    return "${match.groupValues[1]}Z"
}
