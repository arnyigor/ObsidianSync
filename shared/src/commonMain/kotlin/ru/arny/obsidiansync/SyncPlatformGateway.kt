package ru.arny.obsidiansync

import ru.arny.obsidiansync.core.SyncProgressReporter

/**
 * Platform boundary for manual or scheduled synchronization.
 * Desktop file watchers and Android WorkManager can call the same gateway
 * without depending on Compose UI.
 */
interface SyncPlatformGateway {
    fun loadToken(): String? = null
    fun saveToken(token: String) = Unit
    fun clearToken() = Unit
    fun loadLastSynchronization(): SynchronizationResult? = null
    suspend fun checkConnection(token: String): ConnectionResult
    suspend fun scanVault(): VaultScanResult
    suspend fun findRemote(token: String): RemoteScanResult
    suspend fun listPendingConflicts(): List<SyncConflict> = emptyList()
    suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): ConflictResolutionOutcome {
        error("Разрешение конфликтов не поддерживается на этой платформе")
    }
    suspend fun synchronize(
        token: String,
        onProgress: SyncProgressReporter = {},
    ): SynchronizationResult
}

data class SyncConflict(
    val bundleId: String,
    val path: String,
    val changeSummary: String = "",
    val localExcerpt: String = "",
    val remoteExcerpt: String = "",
) {
    val id: String get() = "$bundleId:$path"
}

enum class ConflictResolution {
    KeepRemote,
    RestoreLocal,
    KeepBoth,
}

data class ConflictResolutionOutcome(
    val synchronizedPath: String? = null,
)

fun buildSyncConflict(
    bundleId: String,
    path: String,
    localBytes: ByteArray,
    remoteBytes: ByteArray?,
): SyncConflict {
    val localLines = localBytes.decodeToString().lines()
    if (remoteBytes == null) {
        return SyncConflict(
            bundleId = bundleId,
            path = path,
            changeSummary = "В облаке файл удалён. Локальная версия сохранена полностью.",
            localExcerpt = formatConflictExcerpt(localLines, startIndex = 0),
            remoteExcerpt = "Файл отсутствует — он был удалён в облаке.",
        )
    }

    val remoteLines = remoteBytes.decodeToString().lines()
    var commonPrefix = 0
    while (
        commonPrefix < localLines.size &&
        commonPrefix < remoteLines.size &&
        localLines[commonPrefix] == remoteLines[commonPrefix]
    ) {
        commonPrefix++
    }
    var commonSuffix = 0
    while (
        commonSuffix < localLines.size - commonPrefix &&
        commonSuffix < remoteLines.size - commonPrefix &&
        localLines[localLines.lastIndex - commonSuffix] == remoteLines[remoteLines.lastIndex - commonSuffix]
    ) {
        commonSuffix++
    }
    val localChanged = localLines.subList(commonPrefix, localLines.size - commonSuffix)
    val remoteChanged = remoteLines.subList(commonPrefix, remoteLines.size - commonSuffix)
    val firstLine = commonPrefix + 1
    return SyncConflict(
        bundleId = bundleId,
        path = path,
        changeSummary = "Различие начинается со строки $firstLine. " +
            "Локально строк: ${localChanged.size}, в облаке: ${remoteChanged.size}.",
        localExcerpt = formatConflictExcerpt(localChanged, commonPrefix),
        remoteExcerpt = formatConflictExcerpt(remoteChanged, commonPrefix),
    )
}

private fun formatConflictExcerpt(lines: List<String>, startIndex: Int): String {
    if (lines.isEmpty()) return "— строк нет —"
    val visible = lines.take(MAX_CONFLICT_PREVIEW_LINES)
        .mapIndexed { index, line -> "${startIndex + index + 1} │ ${line.take(MAX_CONFLICT_LINE_LENGTH)}" }
    return buildString {
        append(visible.joinToString("\n"))
        if (lines.size > visible.size) append("\n… ещё строк: ${lines.size - visible.size}")
    }
}

private const val MAX_CONFLICT_PREVIEW_LINES = 8
private const val MAX_CONFLICT_LINE_LENGTH = 160
