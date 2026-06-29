package ru.arny.obsidiansync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictPreviewTest {
    @Test
    fun previewShowsOnlyChangedMiddleSectionWithLineNumbers() {
        val conflict = buildSyncConflict(
            bundleId = "desktop_000001",
            path = "Notes/plan.md",
            localBytes = "same\nmy local text\nsame tail".encodeToByteArray(),
            remoteBytes = "same\ntext from cloud\nsame tail".encodeToByteArray(),
        )

        assertTrue(conflict.changeSummary.contains("строки 2"))
        assertEquals("2 │ my local text", conflict.localExcerpt)
        assertEquals("2 │ text from cloud", conflict.remoteExcerpt)
    }

    @Test
    fun previewExplainsRemoteDeletion() {
        val conflict = buildSyncConflict(
            bundleId = "android_000001",
            path = "Notes/deleted.md",
            localBytes = "keep me".encodeToByteArray(),
            remoteBytes = null,
        )

        assertTrue(conflict.changeSummary.contains("удалён"))
        assertTrue(conflict.remoteExcerpt.contains("отсутствует"))
    }
}
