package ru.arny.obsidiansync.core

import kotlinx.serialization.Serializable

@Serializable
data class VaultFileSnapshot(
    val path: String,
    val hash: String?,
    val size: Long,
    val modifiedAt: Long? = null,
    val deleted: Boolean = false,
)

data class VaultChange(
    val path: String,
    val operation: ManifestOperation,
    val baseHash: String?,
    val newHash: String?,
    val size: Long,
    val modifiedAt: Long? = null,
    val fileKind: FileKind = classifyVaultFile(path),
)

data class VaultDiff(
    val created: List<VaultChange>,
    val modified: List<VaultChange>,
    val deleted: List<VaultChange>,
    val ignored: List<String>,
) {
    val allChanges: List<VaultChange> get() = created + modified + deleted
}

fun SyncManifest.fileSnapshots(): List<VaultFileSnapshot> =
    snapshot.ifEmpty {
        changes
            .filter { it.operation != ManifestOperation.Delete }
            .map { change ->
                VaultFileSnapshot(
                    path = change.path,
                    hash = change.newHash,
                    size = change.size,
                    modifiedAt = change.modifiedAt,
                )
            }
    }

fun diffVaultSnapshots(
    previous: List<VaultFileSnapshot>,
    current: List<VaultFileSnapshot>,
    ignore: ObsideltaIgnore = ObsideltaIgnore(),
): VaultDiff {
    val previousByPath = previous.associateBy { normalizeVaultPath(it.path) }
    val currentByPath = current.associateBy { normalizeVaultPath(it.path) }
    val ignored = (previousByPath.keys + currentByPath.keys)
        .filter { ignore.isIgnored(it) }
        .sorted()

    val comparablePaths = (previousByPath.keys + currentByPath.keys)
        .filterNot { ignore.isIgnored(it) }
        .sorted()

    val created = mutableListOf<VaultChange>()
    val modified = mutableListOf<VaultChange>()
    val deleted = mutableListOf<VaultChange>()

    comparablePaths.forEach { path ->
        val before = previousByPath[path]
        val after = currentByPath[path]
        when {
            before == null && after != null && !after.deleted -> created += VaultChange(
                path = path,
                operation = ManifestOperation.Create,
                baseHash = null,
                newHash = after.hash,
                size = after.size,
                modifiedAt = after.modifiedAt,
            )
            before != null && (after == null || after.deleted) && !before.deleted -> deleted += VaultChange(
                path = path,
                operation = ManifestOperation.Delete,
                baseHash = before.hash,
                newHash = null,
                size = 0,
                modifiedAt = after?.modifiedAt,
            )
            before != null && after != null && before.hash != after.hash && !after.deleted -> modified += VaultChange(
                path = path,
                operation = ManifestOperation.Modify,
                baseHash = before.hash,
                newHash = after.hash,
                size = after.size,
                modifiedAt = after.modifiedAt,
            )
        }
    }

    return VaultDiff(created = created, modified = modified, deleted = deleted, ignored = ignored)
}
