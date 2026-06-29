package ru.arny.obsidiansync

import ru.arny.obsidiansync.core.SyncProgressReporter
import ru.arny.obsidiansync.core.SyncStage
import ru.arny.obsidiansync.core.VaultFileSnapshot
import ru.arny.obsidiansync.core.report

/** Safe, deterministic data used only for documentation screenshots. */
class DemoSyncGateway : SyncPlatformGateway {
    override fun loadToken(): String = "documentation-demo-token"

    override suspend fun checkConnection(token: String) = ConnectionResult(
        ok = true,
        message = "Яндекс Диск подключён. Демонстрационные данные.",
        freeSpace = 18_790_481_920,
    )

    override fun loadLastSynchronization() = SynchronizationResult(
        completedAt = "2026-06-29T12:40:00Z",
        downloadedFiles = 0,
        uploadedFiles = 0,
        conflicts = 0,
        remoteBundles = 2,
        publishedBundleId = "",
        message = "Синхронизация завершена. Изменений нет — новый пакет не создан.",
    )

    override suspend fun scanVault() = VaultScanResult(
        totalFiles = 7,
        supportedFiles = DEMO_FILES.size,
        ignoredFiles = 2,
        files = DEMO_FILES,
    )

    override suspend fun findRemote(token: String) = RemoteScanResult(
        bundlesCount = 2,
        newestBundleId = "desktop_000002_demo",
        message = "Облако проверено. Локальная и облачная версии совпадают.",
        files = DEMO_FILES,
    )

    override suspend fun synchronize(
        token: String,
        onProgress: SyncProgressReporter,
    ): SynchronizationResult {
        onProgress.report(SyncStage.CheckingRemote, "Проверяю облачные пакеты…")
        onProgress.report(SyncStage.BuildingSnapshot, "Сравниваю SHA-256 снимки файлов…")
        return loadLastSynchronization()
    }
}

private val DEMO_FILES = listOf(
    VaultFileSnapshot("Notes/Welcome.md", "c1".repeat(32), 1_248, 1_751_200_000_000),
    VaultFileSnapshot("Projects/Release plan.md", "a7".repeat(32), 2_612, 1_751_210_000_000),
    VaultFileSnapshot("Daily/2026-06-29.md", "3e".repeat(32), 894, 1_751_220_000_000),
    VaultFileSnapshot("Canvas/Overview.canvas", "9b".repeat(32), 4_096, 1_751_230_000_000),
    VaultFileSnapshot("Templates/Meeting.md", "74".repeat(32), 756, 1_751_240_000_000),
)
