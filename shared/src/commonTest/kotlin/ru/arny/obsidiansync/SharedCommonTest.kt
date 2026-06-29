package ru.arny.obsidiansync

import ru.arny.obsidiansync.core.FileKind
import ru.arny.obsidiansync.core.ManifestBundle
import ru.arny.obsidiansync.core.ManifestChange
import ru.arny.obsidiansync.core.ManifestOperation
import ru.arny.obsidiansync.core.ManifestRemote
import ru.arny.obsidiansync.core.ManifestStats
import ru.arny.obsidiansync.core.ObsideltaIgnore
import ru.arny.obsidiansync.core.RemoteLayout
import ru.arny.obsidiansync.core.RemotePath
import ru.arny.obsidiansync.core.SyncManifest
import ru.arny.obsidiansync.core.SyncProgress
import ru.arny.obsidiansync.core.SyncStage
import ru.arny.obsidiansync.core.VaultId
import ru.arny.obsidiansync.core.buildBundleId
import ru.arny.obsidiansync.core.VaultFileSnapshot
import ru.arny.obsidiansync.core.classifyVaultFile
import ru.arny.obsidiansync.core.createdAtFromBundleId
import ru.arny.obsidiansync.core.diffVaultSnapshots
import ru.arny.obsidiansync.core.fileSnapshots
import ru.arny.obsidiansync.core.normalizeVaultPath
import ru.arny.obsidiansync.core.sha256Hex
import ru.arny.obsidiansync.core.syncManifestFromJson
import ru.arny.obsidiansync.core.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedCommonTest {

    @Test
    fun pathNormalizationRemovesDotSegments() {
        assertEquals("Daily/2026-06-28.md", normalizeVaultPath("/Daily/./Drafts/../2026-06-28.md"))
    }

    @Test
    fun defaultIgnoreRulesProtectInternalState() {
        val ignore = ObsideltaIgnore()
        assertTrue(ignore.isIgnored(".obsidelta/state.db"))
        assertTrue(ignore.isIgnored(".obsidian/workspace.json"))
        assertFalse(ignore.isIgnored("Inbox.md"))
    }

    @Test
    fun fileClassificationMatchesMvpRules() {
        assertEquals(FileKind.Text, classifyVaultFile("Inbox.md"))
        assertEquals(FileKind.Media, classifyVaultFile("Assets/image.webp"))
        assertEquals(FileKind.Binary, classifyVaultFile("Models/model.gguf"))
        assertEquals(FileKind.Settings, classifyVaultFile(".obsidian/app.json"))
    }

    @Test
    fun snapshotDiffFindsCreateModifyDeleteAndIgnored() {
        val diff = diffVaultSnapshots(
            previous = listOf(
                VaultFileSnapshot("Inbox.md", "old", 10),
                VaultFileSnapshot("Old.md", "deleted", 20),
                VaultFileSnapshot(".obsidelta/state.db", "internal", 1),
            ),
            current = listOf(
                VaultFileSnapshot("Inbox.md", "new", 11),
                VaultFileSnapshot("New.md", "created", 12),
                VaultFileSnapshot(".obsidelta/state.db", "changed", 2),
            ),
        )
        assertEquals(listOf("New.md"), diff.created.map { it.path })
        assertEquals(listOf("Inbox.md"), diff.modified.map { it.path })
        assertEquals(listOf("Old.md"), diff.deleted.map { it.path })
        assertEquals(listOf(".obsidelta/state.db"), diff.ignored)
    }

    @Test
    fun latestManifestCanBeComparedWithCurrentVault() {
        val remote = sampleManifest().fileSnapshots()
        val local = listOf(
            VaultFileSnapshot("Inbox.md", "new-local-hash", 4096),
            VaultFileSnapshot("LocalOnly.md", "local-only-hash", 128),
        )

        val diff = diffVaultSnapshots(previous = remote, current = local)

        assertEquals(listOf("LocalOnly.md"), diff.created.map { it.path })
        assertEquals(listOf("Inbox.md"), diff.modified.map { it.path })
        assertTrue(diff.deleted.isEmpty())
    }

    @Test
    fun syncProgressIncludesStageAndFileProgress() {
        val progress = SyncProgress(
            stage = SyncStage.BuildingSnapshot,
            message = "Building",
            completedItems = 5,
            totalItems = 10,
        )

        assertEquals(5, progress.stage.step)
        assertTrue(progress.fraction > 0.4f)
        assertTrue(progress.fraction < 0.6f)
        assertEquals(1f, SyncProgress(SyncStage.Completed, "Done").fraction)
    }

    @Test
    fun manifestRoundTripKeepsProtocolFields() {
        val manifest = sampleManifest()
        val restored = syncManifestFromJson(manifest.toJson())
        assertEquals(2, restored.formatVersion)
        assertEquals("obsidelta", restored.protocol)
        assertEquals("desktop_000001_20260628T010000Z", restored.bundleId)
        assertEquals(1, restored.changes.size)
        assertEquals(listOf("Inbox.md"), restored.snapshot.map { it.path })
    }

    @Test
    fun remoteLayoutUsesImmutableJournalPaths() {
        val layout = RemoteLayout(RemotePath("disk:/ObsidianSyncTest"))
        val vaultId = VaultId("main")
        assertEquals(
            "disk:/ObsidianSyncTest/vaults/main/manifests/2026/06/desktop_000001_20260628T010000Z.manifest.json",
            layout.manifestPath(vaultId, "desktop_000001_20260628T010000Z", "2026-06-28T01:00:00Z").value,
        )
        assertEquals(
            "disk:/ObsidianSyncTest/vaults/main/ready/desktop_000001_20260628T010000Z.ready",
            layout.readyMarkerPath(vaultId, "desktop_000001_20260628T010000Z").value,
        )
    }

    @Test
    fun bundleIdIsStableAndSortable() {
        assertEquals("desktop_000014_20260628T010000Z", buildBundleId("Desktop", 14, "2026-06-28T01:00:00Z"))
    }

    @Test
    fun sha256UsesLowercaseHex() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", sha256Hex("hello".encodeToByteArray()))
    }

    @Test
    fun bundleTimestampParserAcceptsLegacyFractionalDesktopIds() {
        assertEquals(
            "2026-06-28T08:27:15Z",
            createdAtFromBundleId("desktop_000001_20260628T082715084464600Z"),
        )
    }

    private fun sampleManifest(): SyncManifest = SyncManifest(
        vaultId = "main-vault-id",
        bundleId = "desktop_000001_20260628T010000Z",
        deviceId = "desktop",
        deviceName = "Windows PC",
        createdAt = "2026-06-28T01:00:00Z",
        sequence = 1,
        remote = ManifestRemote(
            backend = "yandex_disk_rest",
            root = "disk:/ObsiDeltaSync",
            manifestPath = "disk:/ObsiDeltaSync/vaults/main-vault-id/manifests/2026/06/desktop_000001_20260628T010000Z.manifest.json",
            bundlePath = "disk:/ObsiDeltaSync/vaults/main-vault-id/bundles/2026/06/desktop_000001_20260628T010000Z.bundle.enc",
            readyMarkerPath = "disk:/ObsiDeltaSync/vaults/main-vault-id/ready/desktop_000001_20260628T010000Z.ready",
        ),
        bundle = ManifestBundle(
            fileName = "desktop_000001_20260628T010000Z.bundle.enc",
            size = 12345,
            hash = "bundle-sha256",
            encrypted = true,
        ),
        changes = listOf(
            ManifestChange(
                path = "Inbox.md",
                operation = ManifestOperation.Modify,
                baseHash = "hash-before-change",
                newHash = "hash-after-change",
                size = 2048,
                modifiedAt = 1782552000,
                contentType = "text/markdown",
                fileKind = FileKind.Text,
            ),
        ),
        snapshot = listOf(
            VaultFileSnapshot(
                path = "Inbox.md",
                hash = "hash-after-change",
                size = 2048,
                modifiedAt = 1782552000,
            ),
        ),
        stats = ManifestStats(created = 0, modified = 1, deleted = 0, totalUncompressedSize = 2048),
    )
}
