package ru.arny.obsidiansync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopConflictResolutionTest {
    @Test
    fun restoreLocalUsesNewestCopyAndClosesAllCopiesForTheFile() = withVault { root, gateway ->
        val relative = "Notes/plan.md"
        val target = root.resolve(relative).apply {
            Files.createDirectories(parent)
            writeText("remote")
        }
        conflictBackup(root, "desktop_000001", relative, "older local")
        conflictBackup(root, "desktop_000002", relative, "newest local")

        val conflicts = gateway.listPendingConflicts()
        assertEquals(1, conflicts.size)
        assertEquals("desktop_000002", conflicts.single().bundleId)
        assertEquals(relative, conflicts.single().path)
        assertTrue(conflicts.single().localExcerpt.contains("newest local"))
        assertTrue(conflicts.single().remoteExcerpt.contains("remote"))

        gateway.resolveConflict(conflicts.single(), ConflictResolution.RestoreLocal)

        assertEquals("newest local", target.readText())
        assertFalse(Files.exists(root.resolve(".obsidelta/backups/desktop_000001/$relative")))
        assertFalse(Files.exists(root.resolve(".obsidelta/backups/desktop_000002/$relative")))
        assertEquals(emptyList(), gateway.listPendingConflicts())
    }

    @Test
    fun keepBothCreatesSiblingCopyAndLeavesRemoteVersionInPlace() = withVault { root, gateway ->
        val relative = "Notes/plan.md"
        val target = root.resolve(relative).apply {
            Files.createDirectories(parent)
            writeText("remote")
        }
        conflictBackup(root, "desktop_000004_20260629T120000Z", relative, "local")
        val conflict = gateway.listPendingConflicts().single()

        val outcome = gateway.resolveConflict(conflict, ConflictResolution.KeepBoth)

        val copyPath = assertNotNull(outcome.synchronizedPath)
        assertEquals("remote", target.readText())
        assertEquals("local", root.resolve(copyPath).readText())
        assertTrue(copyPath.startsWith("Notes/plan.local-copy-20260629T120000Z"))
        assertEquals(emptyList(), gateway.listPendingConflicts())
    }

    @Test
    fun keepRemoteLeavesCurrentVaultFileAndDeletesLocalCopy() = withVault { root, gateway ->
        val relative = "Notes/plan.md"
        val target = root.resolve(relative).apply {
            Files.createDirectories(parent)
            writeText("remote")
        }
        conflictBackup(root, "desktop_000003", relative, "local")
        val conflict = gateway.listPendingConflicts().single()

        gateway.resolveConflict(conflict, ConflictResolution.KeepRemote)

        assertEquals("remote", target.readText())
        assertEquals(emptyList(), gateway.listPendingConflicts())
    }

    private fun conflictBackup(root: Path, bundleId: String, relative: String, content: String) {
        root.resolve(".obsidelta/backups/$bundleId/$relative").apply {
            Files.createDirectories(parent)
            writeText(content)
        }
    }

    private fun withVault(block: suspend (Path, DesktopSyncGateway) -> Unit) = runBlocking {
        val root = createTempDirectory("obsidelta-conflict-test")
        try {
            block(root, DesktopSyncGateway { root })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
