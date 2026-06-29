package ru.arny.obsidiansync.core

sealed class ApplyDecision {
    data object SafeApply : ApplyDecision()
    data class Conflict(val type: ConflictType, val reason: String) : ApplyDecision()
}

enum class ConflictType {
    ModifyVsModify,
    DeleteVsModify,
    CreateVsCreateSamePath,
    MissingBase,
    HashMismatch,
}

fun detectApplyDecision(
    operation: ManifestOperation,
    baseHash: String?,
    localHash: String?,
    remoteHash: String?,
    localExists: Boolean,
): ApplyDecision = when (operation) {
    ManifestOperation.Create -> {
        if (!localExists) ApplyDecision.SafeApply
        else ApplyDecision.Conflict(
            ConflictType.CreateVsCreateSamePath,
            "Remote create targets an existing local file.",
        )
    }
    ManifestOperation.Modify -> {
        when {
            baseHash == null -> ApplyDecision.Conflict(ConflictType.MissingBase, "Remote modify has no base hash.")
            localHash == baseHash -> ApplyDecision.SafeApply
            !localExists -> ApplyDecision.Conflict(ConflictType.MissingBase, "Local file is missing but remote expects a base file.")
            localHash == remoteHash -> ApplyDecision.SafeApply
            else -> ApplyDecision.Conflict(ConflictType.ModifyVsModify, "Local hash differs from remote base hash.")
        }
    }
    ManifestOperation.Delete -> {
        when {
            baseHash == null -> ApplyDecision.Conflict(ConflictType.MissingBase, "Remote delete has no base hash.")
            !localExists -> ApplyDecision.SafeApply
            localHash == baseHash -> ApplyDecision.SafeApply
            else -> ApplyDecision.Conflict(ConflictType.DeleteVsModify, "Local file changed after remote delete base.")
        }
    }
}
