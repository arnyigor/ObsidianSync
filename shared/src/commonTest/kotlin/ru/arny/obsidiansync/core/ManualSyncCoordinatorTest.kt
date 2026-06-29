package ru.arny.obsidiansync.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualSyncCoordinatorTest {
    @Test
    fun matchingVaultDoesNotApplyOrPublishEvenWhenLocalTrackingIsMissing() = runTest {
        val snapshot = listOf(VaultFileSnapshot("Inbox.md", "same-hash", 10))
        val latest = manifest(snapshot)
        val storage = RecordingRemoteStorage(latest)
        val coordinator = coordinator(storage)
        val marked = mutableListOf<String>()
        var applyCalls = 0

        val result = coordinator.synchronize(
            createdAt = "2026-06-29T12:00:00Z",
            sequence = 2,
            currentSnapshot = snapshot,
            isApplied = { false },
            applyBundle = { _, _, _ ->
                applyCalls++
                ApplyBundleResult(0, 0)
            },
            buildSnapshot = { _, _, _, _, _ ->
                SnapshotPackage(
                    bytes = ByteArray(0),
                    files = emptyList(),
                    deletes = emptyList(),
                    snapshot = snapshot,
                    checkpoint = false,
                )
            },
            markApplied = { marked += it },
        )

        assertFalse(result.published)
        assertEquals(0, result.uploadedFiles)
        assertEquals(1, result.remoteBundles)
        assertEquals(0, applyCalls)
        assertEquals(listOf(latest.bundleId), marked)
        assertTrue(storage.uploadedPaths.isEmpty())
    }

    @Test
    fun changedVaultPublishesDeltaPackage() = runTest {
        val latest = manifest(listOf(VaultFileSnapshot("Inbox.md", "old-hash", 10)))
        val storage = RecordingRemoteStorage(latest)
        val coordinator = coordinator(storage)
        val current = listOf(VaultFileSnapshot("Inbox.md", "new-hash", 11))

        val result = coordinator.synchronize(
            createdAt = "2026-06-29T12:00:00Z",
            sequence = 2,
            currentSnapshot = current,
            isApplied = { true },
            applyBundle = { _, _, _ -> ApplyBundleResult(0, 0) },
            buildSnapshot = { _, _, _, _, _ ->
                SnapshotPackage(
                    bytes = "delta".encodeToByteArray(),
                    files = listOf(
                        ManifestChange(
                            path = "Inbox.md",
                            operation = ManifestOperation.Modify,
                            baseHash = "old-hash",
                            newHash = "new-hash",
                            size = 11,
                        ),
                    ),
                    deletes = emptyList(),
                    snapshot = current,
                    checkpoint = false,
                )
            },
            markApplied = {},
        )

        assertTrue(result.published)
        assertEquals(1, result.uploadedFiles)
        assertEquals(2, result.remoteBundles)
        assertEquals(3, storage.uploadedPaths.size)
    }

    @Test
    fun remotePackageIsAppliedOnlyOnceAcrossRepeatedSynchronization() = runTest {
        val remoteSnapshot = listOf(VaultFileSnapshot("Inbox.md", "remote-hash", 10))
        val latest = manifest(remoteSnapshot, deviceId = "android")
        val storage = RecordingRemoteStorage(latest)
        val applied = mutableSetOf<String>()
        var applyCalls = 0

        suspend fun synchronize(current: List<VaultFileSnapshot>) = coordinator(storage).synchronize(
            createdAt = "2026-06-29T12:00:00Z",
            sequence = 2,
            currentSnapshot = current,
            isApplied = { it in applied },
            applyBundle = { _, _, _ ->
                applyCalls++
                ApplyBundleResult(appliedFiles = 1, conflicts = 0)
            },
            buildSnapshot = { _, _, _, _, _ ->
                SnapshotPackage(
                    bytes = ByteArray(0),
                    files = emptyList(),
                    deletes = emptyList(),
                    snapshot = remoteSnapshot,
                    checkpoint = false,
                )
            },
            markApplied = { applied += it },
        )

        synchronize(listOf(VaultFileSnapshot("Inbox.md", "old-local-hash", 10)))
        synchronize(remoteSnapshot)

        assertEquals(1, applyCalls)
        assertEquals(setOf(latest.bundleId), applied)
        assertTrue(storage.uploadedPaths.isEmpty())
    }

    @Test
    fun journalLimitCreatesCheckpointAndDeletesOldPackages() = runTest {
        val current = listOf(VaultFileSnapshot("Inbox.md", "same-hash", 10))
        val latest = manifest(current, sequence = ManualSyncCoordinator.MAX_REMOTE_PACKAGES)
        val storage = RecordingRemoteStorage(
            latest = latest,
            markerCount = ManualSyncCoordinator.MAX_REMOTE_PACKAGES,
        )
        var checkpointRequested = false

        val result = coordinator(storage).synchronize(
            createdAt = "2026-06-29T12:00:00Z",
            sequence = 11,
            currentSnapshot = current,
            isApplied = { true },
            applyBundle = { _, _, _ -> ApplyBundleResult(0, 0) },
            buildSnapshot = { _, _, _, forceCheckpoint, _ ->
                checkpointRequested = forceCheckpoint
                SnapshotPackage(
                    bytes = "full-checkpoint".encodeToByteArray(),
                    files = listOf(
                        ManifestChange(
                            path = "Inbox.md",
                            operation = ManifestOperation.Create,
                            newHash = "same-hash",
                            size = 10,
                        ),
                    ),
                    deletes = emptyList(),
                    snapshot = current,
                    checkpoint = forceCheckpoint,
                )
            },
            markApplied = {},
        )

        assertTrue(checkpointRequested)
        assertTrue(result.published)
        assertTrue(result.checkpoint)
        assertEquals(ManualSyncCoordinator.MAX_REMOTE_PACKAGES, result.compactedPackages)
        assertEquals(1, result.remoteBundles)
        assertEquals(ManualSyncCoordinator.MAX_REMOTE_PACKAGES * 3, storage.deletedPaths.size)
    }

    private fun coordinator(storage: RemoteStorage) = ManualSyncCoordinator(
        remoteStorage = storage,
        remoteRoot = RemotePath("disk:/ObsidianSyncTest"),
        vaultId = VaultId("default-vault"),
        deviceId = "desktop",
        deviceName = "Windows Desktop",
    )

    private fun manifest(
        snapshot: List<VaultFileSnapshot>,
        sequence: Int = 1,
        deviceId: String = "desktop",
    ): SyncManifest {
        val bundleId = "desktop_${sequence.toString().padStart(6, '0')}_20260629T110000Z"
        return SyncManifest(
            vaultId = "default-vault",
            bundleId = bundleId,
            deviceId = deviceId,
            deviceName = deviceId,
            createdAt = "2026-06-29T11:00:00Z",
            sequence = sequence,
            remote = ManifestRemote(
                backend = "test",
                root = "disk:/ObsidianSyncTest",
                manifestPath = "disk:/ObsidianSyncTest/manifest.json",
                bundlePath = "disk:/ObsidianSyncTest/bundle.zip",
                readyMarkerPath = "disk:/ObsidianSyncTest/$bundleId.ready",
            ),
            bundle = ManifestBundle(
                fileName = "bundle.zip",
                size = 0,
                hash = sha256Hex(ByteArray(0)),
                encrypted = false,
            ),
            snapshot = snapshot,
        )
    }
}

private class RecordingRemoteStorage(
    private val latest: SyncManifest,
    private val markerCount: Int = 1,
) : RemoteStorage {
    val uploadedPaths = mutableListOf<String>()
    val deletedPaths = mutableListOf<String>()

    override suspend fun checkConnection() = RemoteCheckResult(true, "test", "ok")
    override suspend fun getQuota(): RemoteQuota? = null
    override suspend fun ensureDirectory(path: RemotePath) = Unit
    override suspend fun exists(path: RemotePath) = false
    override suspend fun list(path: RemotePath): List<RemoteObject> = emptyList()

    override suspend fun uploadBytes(path: RemotePath, bytes: ByteArray, overwrite: Boolean): RemoteUploadResult {
        uploadedPaths += path.value
        return RemoteUploadResult(path.value, bytes.size.toLong(), overwrite)
    }

    override suspend fun downloadBytes(path: RemotePath): ByteArray = ByteArray(0)
    override suspend fun delete(path: RemotePath, permanently: Boolean) {
        deletedPaths += path.value
    }
    override suspend fun move(from: RemotePath, to: RemotePath, overwrite: Boolean) = Unit

    override suspend fun listReadyMarkers(vaultId: VaultId) = (1..markerCount).map { index ->
        val bundleId = if (index == markerCount) {
            latest.bundleId
        } else {
            "desktop_${index.toString().padStart(6, '0')}_20260629T1100${index.toString().padStart(2, '0')}Z"
        }
        ReadyMarker(bundleId, "disk:/ObsidianSyncTest/$bundleId.ready", latest.createdAt)
    }

    override suspend fun uploadManifest(manifest: SyncManifest) = Unit
    override suspend fun downloadManifest(ref: ManifestRef) = latest
    override suspend fun uploadBundle(bundle: EncryptedBundle) = Unit
    override suspend fun downloadBundle(ref: BundleRef): ByteArray = ByteArray(0)
}
