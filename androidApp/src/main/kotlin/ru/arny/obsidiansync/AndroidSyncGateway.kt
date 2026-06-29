package ru.arny.obsidiansync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.arny.obsidiansync.core.ApplyBundleResult
import ru.arny.obsidiansync.core.FileKind
import ru.arny.obsidiansync.core.ManifestChange
import ru.arny.obsidiansync.core.ManifestDelete
import ru.arny.obsidiansync.core.ManifestOperation
import ru.arny.obsidiansync.core.ManualSyncCoordinator
import ru.arny.obsidiansync.core.ObsideltaIgnore
import ru.arny.obsidiansync.core.RemotePath
import ru.arny.obsidiansync.core.SnapshotPackage
import ru.arny.obsidiansync.core.SyncManifest
import ru.arny.obsidiansync.core.SyncProgressReporter
import ru.arny.obsidiansync.core.SyncStage
import ru.arny.obsidiansync.core.VaultFileSnapshot
import ru.arny.obsidiansync.core.VaultId
import ru.arny.obsidiansync.core.classifyVaultFile
import ru.arny.obsidiansync.core.diffVaultSnapshots
import ru.arny.obsidiansync.core.fileSnapshots
import ru.arny.obsidiansync.core.report
import ru.arny.obsidiansync.core.sha256Hex
import ru.arny.obsidiansync.remote.yandex.YandexDiskRestRemoteStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AndroidSyncGateway(
    context: Context,
    private val vaultUriProvider: () -> Uri?,
) : SyncPlatformGateway {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("obsidelta_sync_state", Context.MODE_PRIVATE)
    private val tokenStore = AndroidTokenStore(appContext)
    private val ignore = ObsideltaIgnore()

    override fun loadToken(): String? = tokenStore.load()

    override fun saveToken(token: String) = tokenStore.save(token)

    override fun clearToken() = tokenStore.clear()

    override fun loadLastSynchronization(): SynchronizationResult? {
        val completedAt = preferences.getString("last_completed_at", null) ?: return null
        return SynchronizationResult(
            completedAt = completedAt,
            downloadedFiles = preferences.getInt("last_downloaded", 0),
            uploadedFiles = preferences.getInt("last_uploaded", 0),
            conflicts = preferences.getInt("last_backups", 0),
            remoteBundles = preferences.getInt("last_remote_bundles", 0),
            publishedBundleId = preferences.getString("last_bundle_id", "").orEmpty(),
            message = preferences.getString("last_message", "Синхронизация завершена.").orEmpty(),
        )
    }

    override suspend fun listPendingConflicts(): List<SyncConflict> = withContext(Dispatchers.IO) {
        val root = vaultRoot()
        val backupRoot = findDocument(root, ".obsidelta/backups")
            ?.takeIf { it.isDirectory }
            ?: return@withContext emptyList()
        backupRoot.listFiles()
            .filter { it.isDirectory && it.name !in NON_CONFLICT_BACKUP_DIRECTORIES }
            .flatMap { bundleDirectory ->
                val bundleId = bundleDirectory.name ?: return@flatMap emptyList()
                buildList { collectBackupConflicts(root, bundleDirectory, bundleId, "", this) }
            }
            .sortedWith(compareByDescending<SyncConflict> { it.bundleId }.thenBy { it.path })
            .distinctBy { it.path }
    }

    override suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): ConflictResolutionOutcome = withContext(Dispatchers.IO) {
        val root = vaultRoot()
        val backupRoot = findDocument(root, ".obsidelta/backups")
            ?: error("Папка резервных копий конфликтов не найдена")
        val bundleDirectory = backupRoot.findFile(conflict.bundleId)
            ?.takeIf { it.isDirectory }
            ?: error("Локальная копия конфликта больше не существует: ${conflict.path}")
        val backup = findDocument(bundleDirectory, conflict.path)
            ?.takeIf { it.isFile }
            ?: error("Локальная копия конфликта больше не существует: ${conflict.path}")
        val synchronizedPath = when (resolution) {
            ConflictResolution.KeepRemote -> null
            ConflictResolution.RestoreLocal -> {
                val bytes = readBytes(backup)
                val (parent, name) = resolveParent(root, conflict.path)
                parent.findFile(name)?.let { overwriteFile(it, bytes) } ?: writeNewFile(parent, name, bytes)
                conflict.path
            }
            ConflictResolution.KeepBoth -> {
                val bytes = readBytes(backup)
                val copyPath = uniqueConflictCopyPath(root, conflict)
                val (parent, name) = resolveParent(root, copyPath)
                writeNewFile(parent, name, bytes)
                copyPath
            }
        }
        backupRoot.listFiles()
            .filter { it.isDirectory && it.name !in NON_CONFLICT_BACKUP_DIRECTORIES }
            .forEach { candidateBundle ->
                findDocument(candidateBundle, conflict.path)?.takeIf { it.isFile }?.let { candidate ->
                    check(candidate.delete()) { "Не удалось закрыть конфликт: ${conflict.path}" }
                }
                if (!containsFile(candidateBundle)) candidateBundle.delete()
            }
        ConflictResolutionOutcome(synchronizedPath = synchronizedPath)
    }

    override suspend fun checkConnection(token: String): ConnectionResult = withRemote(token) { remote ->
        val result = remote.checkConnection()
        ConnectionResult(
            ok = result.ok,
            message = if (result.ok) "Яндекс Диск подключен." else "Подключение отклонено: ${result.message}",
            freeSpace = result.quota?.freeSpace,
        )
    }

    override suspend fun scanVault(): VaultScanResult = withContext(Dispatchers.IO) {
        val all = collectFiles()
        val supportedFiles = all.filter { isSupported(it.path) }
        val files = supportedFiles.map { file ->
            val bytes = readBytes(file.document)
            VaultFileSnapshot(
                path = file.path,
                hash = sha256Hex(bytes),
                size = bytes.size.toLong(),
                modifiedAt = file.document.lastModified().takeIf { it > 0 },
            )
        }
        VaultScanResult(
            totalFiles = all.size,
            supportedFiles = supportedFiles.size,
            ignoredFiles = all.size - supportedFiles.size,
            files = files,
        )
    }

    override suspend fun findRemote(token: String): RemoteScanResult = withRemote(token) { remote ->
        val (markers, latest) = coordinator(remote).findRemote()
        RemoteScanResult(
            bundlesCount = markers.size,
            newestBundleId = latest?.bundleId,
            message = if (latest == null) "Облачный журнал пуст — первый пакет создаст Android."
            else "Найдено пакетов: ${markers.size}. Последний: ${latest.bundleId}",
            files = latest?.fileSnapshots().orEmpty(),
        )
    }

    override suspend fun synchronize(
        token: String,
        onProgress: SyncProgressReporter,
    ): SynchronizationResult = withRemote(token) { remote ->
        onProgress.report(SyncStage.Preparing, "Подготавливаю локальное состояние…")
        normalizeLegacyMimeNames()
        archiveLegacyConflictCopies()
        val currentSnapshot = scanVault().files
        val completedAt = utcNow()
        val result = coordinator(remote).synchronize(
            createdAt = completedAt,
            sequence = nextSequence(),
            currentSnapshot = currentSnapshot,
            isApplied = ::isApplied,
            applyBundle = ::applyBundle,
            buildSnapshot = ::buildSnapshot,
            markApplied = ::markApplied,
            onProgress = onProgress,
        )
        val summary = SynchronizationResult(
            completedAt = completedAt,
            downloadedFiles = result.downloadedFiles,
            uploadedFiles = result.uploadedFiles,
            conflicts = result.conflicts,
            remoteBundles = result.remoteBundles,
            publishedBundleId = result.publishedBundleId,
            message = if (!result.published) {
                "Синхронизация завершена. Изменений нет — новый пакет не создан."
            } else if (result.checkpoint) {
                "Создан полный checkpoint. Удалено старых пакетов: ${result.compactedPackages}. В облаке остался 1 пакет."
            } else buildString {
                append("Синхронизация завершена. Получено: ${result.downloadedFiles}, опубликовано изменений: ${result.uploadedFiles}.")
            if (result.conflicts > 0) append(" Конфликтов требуют выбора версии: ${result.conflicts}.")
            },
        )
        saveLastSynchronization(summary)
        onProgress.report(SyncStage.Completed, summary.message)
        summary
    }

    private fun saveLastSynchronization(result: SynchronizationResult) {
        preferences.edit()
            .putString("last_completed_at", result.completedAt)
            .putInt("last_downloaded", result.downloadedFiles)
            .putInt("last_uploaded", result.uploadedFiles)
            .putInt("last_backups", result.conflicts)
            .putInt("last_remote_bundles", result.remoteBundles)
            .putString("last_bundle_id", result.publishedBundleId)
            .putString("last_message", result.message)
            .apply()
    }

    private fun coordinator(remote: YandexDiskRestRemoteStorage) = ManualSyncCoordinator(
        remoteStorage = remote,
        remoteRoot = REMOTE_ROOT,
        vaultId = VAULT_ID,
        deviceId = "android",
        deviceName = "Android",
    )

    private suspend fun buildSnapshot(
        bundleId: String,
        createdAt: String,
        previous: List<VaultFileSnapshot>,
        forceCheckpoint: Boolean,
        onProgress: SyncProgressReporter,
    ): SnapshotPackage {
        val files = collectFiles().filter { isSupported(it.path) }
        val current = files.mapIndexed { index, file ->
            val bytes = readBytes(file.document)
            onProgress.report(
                SyncStage.BuildingSnapshot,
                "Проверяю: ${file.path}",
                completedItems = index + 1,
                totalItems = files.size,
            )
            VaultFileSnapshot(
                path = file.path,
                hash = sha256Hex(bytes),
                size = bytes.size.toLong(),
                modifiedAt = file.document.lastModified().takeIf { it > 0 },
            )
        }
        val diff = diffVaultSnapshots(previous = previous, current = current, ignore = ignore)
        val changedPaths = if (forceCheckpoint) {
            current.mapTo(hashSetOf()) { it.path }
        } else {
            (diff.created + diff.modified).mapTo(hashSetOf()) { it.path }
        }
        onProgress.report(
            SyncStage.BuildingSnapshot,
            if (forceCheckpoint) "Создаю полный checkpoint…" else "Упаковываю изменений: ${diff.allChanges.size}…",
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.filter { it.path in changedPaths }.forEach { file ->
                val bytes = readBytes(file.document)
                zip.putNextEntry(ZipEntry("files/${file.path}"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val changes = if (forceCheckpoint) {
            current.map { file ->
                ManifestChange(
                    path = file.path,
                    operation = ManifestOperation.Create,
                    newHash = requireNotNull(file.hash),
                    size = file.size,
                    modifiedAt = file.modifiedAt,
                    contentType = mimeType(file.path),
                    fileKind = classifyVaultFile(file.path),
                )
            }
        } else {
            (diff.created + diff.modified).map { change ->
                ManifestChange(
                    path = change.path,
                    operation = change.operation,
                    baseHash = change.baseHash,
                    newHash = requireNotNull(change.newHash),
                    size = change.size,
                    modifiedAt = change.modifiedAt,
                    contentType = mimeType(change.path),
                    fileKind = change.fileKind,
                )
            }
        }
        val deletes = diff.deleted.map { change ->
            ManifestDelete(
                path = change.path,
                baseHash = requireNotNull(change.baseHash),
                deletedAt = createdAt,
                fileKind = change.fileKind,
            )
        }
        return SnapshotPackage(
            bytes = output.toByteArray(),
            files = changes,
            deletes = deletes,
            snapshot = current,
            checkpoint = forceCheckpoint,
        )
    }

    private suspend fun applyBundle(
        manifest: SyncManifest,
        bytes: ByteArray,
        onProgress: SyncProgressReporter,
    ): ApplyBundleResult {
        val root = vaultRoot()
        var applied = 0
        var conflicts = 0
        var processed = 0
        val totalOperations = manifest.changes.size + manifest.deletes.size
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("files/")) {
                    val path = validatedRelativePath(entry.name.removePrefix("files/"))
                    processed++
                    onProgress.report(
                        SyncStage.ApplyingRemote,
                        "Применяю: $path",
                        completedItems = processed,
                        totalItems = totalOperations,
                    )
                    val remoteBytes = zip.readBytes()
                    val (parent, fileName) = resolveParent(root, path)
                    val existing = parent.findFile(fileName)
                    when {
                        existing == null -> {
                            writeNewFile(parent, fileName, remoteBytes)
                            applied++
                        }
                        !readBytes(existing).contentEquals(remoteBytes) -> {
                            backupLocalFile(root, path, manifest.bundleId, readBytes(existing))
                            overwriteFile(existing, remoteBytes)
                            applied++
                            conflicts++
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        manifest.deletes.forEach { delete ->
            val path = validatedRelativePath(delete.path)
            processed++
            onProgress.report(
                SyncStage.ApplyingRemote,
                "Удаляю: $path",
                completedItems = processed,
                totalItems = totalOperations,
            )
            val existing = findDocument(root, path)
            if (existing != null && existing.isFile) {
                val localBytes = readBytes(existing)
                if (sha256Hex(localBytes) != delete.baseHash) {
                    backupLocalFile(root, path, manifest.bundleId, localBytes)
                    conflicts++
                }
                check(existing.delete()) { "Не удалось удалить $path" }
                applied++
            }
        }
        if (manifest.checkpoint) {
            val checkpointPaths = manifest.snapshot.mapTo(hashSetOf()) { it.path }
            collectFiles().filter { isSupported(it.path) && it.path !in checkpointPaths }.forEach { file ->
                val localBytes = readBytes(file.document)
                backupLocalFile(root, file.path, manifest.bundleId, localBytes)
                check(file.document.delete()) { "Не удалось удалить ${file.path} при применении checkpoint" }
                applied++
                conflicts++
            }
        }
        normalizeLegacyMimeNames()
        return ApplyBundleResult(appliedFiles = applied, conflicts = conflicts)
    }

    private fun collectFiles(): List<AndroidVaultFile> {
        val result = mutableListOf<AndroidVaultFile>()
        collectFiles(vaultRoot(), prefix = "", result = result)
        return result
    }

    private fun collectFiles(
        directory: DocumentFile,
        prefix: String,
        result: MutableList<AndroidVaultFile>,
    ) {
        directory.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (ignore.isIgnored(path)) return@forEach
            when {
                child.isDirectory -> collectFiles(child, path, result)
                child.isFile -> result += AndroidVaultFile(path, child)
            }
        }
    }

    private fun collectBackupConflicts(
        root: DocumentFile,
        directory: DocumentFile,
        bundleId: String,
        prefix: String,
        result: MutableList<SyncConflict>,
    ) {
        directory.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                child.isDirectory -> collectBackupConflicts(root, child, bundleId, path, result)
                child.isFile -> result += buildSyncConflict(
                    bundleId = bundleId,
                    path = path,
                    localBytes = readBytes(child),
                    remoteBytes = findDocument(root, path)?.takeIf { it.isFile }?.let(::readBytes),
                )
            }
        }
    }

    private fun containsFile(directory: DocumentFile): Boolean =
        directory.listFiles().any { child -> child.isFile || (child.isDirectory && containsFile(child)) }

    private fun uniqueConflictCopyPath(root: DocumentFile, conflict: SyncConflict): String {
        val parent = conflict.path.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = conflict.path.substringAfterLast('/')
        val extensionIndex = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val stem = fileName.substring(0, extensionIndex)
        val extension = fileName.substring(extensionIndex)
        val stamp = conflict.bundleId.substringAfterLast('_').filter { it.isLetterOrDigit() }.take(16)
            .ifEmpty { "conflict" }
        var index = 1
        while (true) {
            val suffix = if (index == 1) "" else "-$index"
            val candidateName = "$stem.local-copy-$stamp$suffix$extension"
            val candidate = if (parent.isEmpty()) candidateName else "$parent/$candidateName"
            if (findDocument(root, candidate) == null) return candidate
            index++
        }
    }

    private fun resolveParent(root: DocumentFile, path: String): Pair<DocumentFile, String> {
        val parts = path.split('/')
        var directory = root
        parts.dropLast(1).forEach { segment ->
            directory = directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: directory.createDirectory(segment)
                ?: error("Не удалось создать папку $segment в Android Vault")
        }
        return directory to parts.last()
    }

    private fun findDocument(root: DocumentFile, path: String): DocumentFile? {
        var current: DocumentFile = root
        path.split('/').forEach { segment ->
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun writeNewFile(parent: DocumentFile, name: String, bytes: ByteArray) {
        val document = parent.createFile("application/octet-stream", name)
            ?: error("Не удалось создать $name в Android Vault")
        resolver.openOutputStream(document.uri, "w")?.use { it.write(bytes) }
            ?: error("Не удалось открыть $name для записи")
    }

    private fun overwriteFile(document: DocumentFile, bytes: ByteArray) {
        resolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
            ?: error("Не удалось перезаписать ${document.name ?: document.uri}")
    }

    private fun backupLocalFile(root: DocumentFile, path: String, bundleId: String, bytes: ByteArray) {
        val (parent, name) = resolveParent(root, ".obsidelta/backups/$bundleId/$path")
        parent.findFile(name)?.let { overwriteFile(it, bytes) } ?: writeNewFile(parent, name, bytes)
    }

    private fun archiveLegacyConflictCopies() {
        val root = vaultRoot()
        collectFiles().filter { it.document.name.orEmpty().matches(LEGACY_CONFLICT_FILE) }.forEach { file ->
            val bytes = readBytes(file.document)
            val (parent, name) = resolveParent(root, ".obsidelta/backups/legacy-conflict-copies/${file.path}")
            parent.findFile(name)?.let { overwriteFile(it, bytes) } ?: writeNewFile(parent, name, bytes)
            check(file.document.delete()) { "Не удалось архивировать старую conflict-copy: ${file.path}" }
        }
    }

    private fun normalizeLegacyMimeNames() {
        val root = vaultRoot()
        collectFiles().mapNotNull { file ->
            canonicalMimeName(file.document.name.orEmpty())?.let { canonical -> file to canonical }
        }.forEach { (duplicate, canonicalName) ->
            val (parent, _) = resolveParent(root, duplicate.path)
            val canonical = parent.findFile(canonicalName)
            if (canonical == null) {
                check(duplicate.document.renameTo(canonicalName)) { "Не удалось исправить имя ${duplicate.path}" }
            } else {
                backupLocalFile(
                    root = root,
                    path = duplicate.path,
                    bundleId = "legacy-mime-duplicates",
                    bytes = readBytes(duplicate.document),
                )
                check(duplicate.document.delete()) { "Не удалось архивировать дубликат ${duplicate.path}" }
            }
        }
    }

    private fun readBytes(document: DocumentFile): ByteArray =
        resolver.openInputStream(document.uri)?.use { it.readBytes() }
            ?: error("Не удалось прочитать ${document.name ?: document.uri}")

    private fun vaultRoot(): DocumentFile {
        val uri = requireNotNull(vaultUriProvider()) { "Сначала выберите Obsidian Vault" }
        return requireNotNull(DocumentFile.fromTreeUri(appContext, uri)) { "Android не может открыть выбранный Vault" }
    }

    private fun isApplied(bundleId: String): Boolean = bundleId in preferences.getStringSet(KEY_APPLIED, emptySet()).orEmpty()

    private fun markApplied(bundleId: String) {
        val updated = preferences.getStringSet(KEY_APPLIED, emptySet()).orEmpty().toMutableSet().apply { add(bundleId) }
        preferences.edit().putStringSet(KEY_APPLIED, updated).apply()
    }

    private fun nextSequence(): Int {
        val next = preferences.getInt(KEY_SEQUENCE, 0) + 1
        preferences.edit().putInt(KEY_SEQUENCE, next).commit()
        return next
    }

    private suspend fun <T> withRemote(
        token: String,
        block: suspend (YandexDiskRestRemoteStorage) -> T,
    ): T = withContext(Dispatchers.IO) {
        val client = HttpClient(CIO)
        try {
            block(YandexDiskRestRemoteStorage(client, token, REMOTE_ROOT))
        } finally {
            client.close()
        }
    }

    private data class AndroidVaultFile(val path: String, val document: DocumentFile)

    private companion object {
        val REMOTE_ROOT = RemotePath("disk:/ObsiDeltaSync")
        val VAULT_ID = VaultId("default-vault")
        const val KEY_APPLIED = "applied_bundles"
        const val KEY_SEQUENCE = "android_sequence"
    }
}

private fun isSupported(path: String): Boolean = classifyVaultFile(path) == FileKind.Text

private fun validatedRelativePath(path: String): String {
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.isNotBlank()) { "Пустой путь в bundle" }
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Небезопасный путь в bundle: $path"
    }
    return normalized
}

private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "md", "txt" -> "text/plain"
    "json", "canvas" -> "application/json"
    else -> "application/octet-stream"
}

private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

private val LEGACY_CONFLICT_FILE = Regex(".+\\.remote-(desktop|android)-\\d{8}-?\\d{6}(?:-\\d+)?(?:\\.[^.]+)?$")
private val NON_CONFLICT_BACKUP_DIRECTORIES = setOf("legacy-conflict-copies", "legacy-mime-duplicates")

private fun canonicalMimeName(name: String): String? = when {
    name.endsWith(".md.txt", ignoreCase = true) -> name.dropLast(4)
    name.endsWith(".canvas.json", ignoreCase = true) -> name.dropLast(5)
    else -> null
}
