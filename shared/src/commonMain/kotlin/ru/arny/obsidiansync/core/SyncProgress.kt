package ru.arny.obsidiansync.core

enum class SyncStage(val step: Int) {
    Preparing(1),
    CheckingRemote(2),
    DownloadingRemote(3),
    ApplyingRemote(4),
    BuildingSnapshot(5),
    UploadingBundle(6),
    UploadingManifest(7),
    Publishing(8),
    Completed(9),
    Failed(9),
}

data class SyncProgress(
    val stage: SyncStage,
    val message: String,
    val completedItems: Int? = null,
    val totalItems: Int? = null,
) {
    val fraction: Float
        get() {
            if (stage == SyncStage.Completed || stage == SyncStage.Failed) return 1f
            val itemFraction = if (completedItems != null && totalItems != null && totalItems > 0) {
                completedItems.toFloat() / totalItems
            } else {
                0f
            }
            return ((stage.step - 1 + itemFraction) / TOTAL_STEPS).coerceIn(0f, 1f)
        }

    companion object {
        const val TOTAL_STEPS = 9
    }
}

typealias SyncProgressReporter = suspend (SyncProgress) -> Unit

suspend fun SyncProgressReporter.report(
    stage: SyncStage,
    message: String,
    completedItems: Int? = null,
    totalItems: Int? = null,
) = invoke(SyncProgress(stage, message, completedItems, totalItems))
