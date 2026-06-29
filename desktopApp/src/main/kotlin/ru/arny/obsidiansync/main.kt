package ru.arny.obsidiansync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.prefs.Preferences
import javax.swing.JFileChooser

fun main() = application {
    val demoMode = remember { System.getenv("OBSIDELTA_DEMO").equals("true", ignoreCase = true) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "ObsiDelta Sync — синхронизация Obsidian",
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1180.dp, height = 900.dp),
    ) {
        val settings = remember { Preferences.userNodeForPackage(DesktopSyncGateway::class.java) }
        var vaultPath by remember {
            mutableStateOf(if (demoMode) DEMO_VAULT_PATH else settings.get(KEY_VAULT_PATH, null))
        }
        val syncGateway = remember(demoMode) {
            if (demoMode) DemoSyncGateway() else DesktopSyncGateway { vaultPath?.let(Path::of) }
        }
        App(
            vaultPath = vaultPath,
            onChooseVaultFolder = if (demoMode) null else ({
                chooseVaultFolder()?.let {
                    vaultPath = it
                    settings.put(KEY_VAULT_PATH, it)
                    settings.flush()
                }
            }),
            onOpenYandexLogin = ::openYandexLogin,
            syncGateway = syncGateway,
        )
    }
}

internal class DesktopSyncGateway(
    private val vaultRootProvider: () -> Path?,
) : SyncPlatformGateway {
    private val tokenStore = DesktopTokenStore()
    private val syncPreferences = Preferences.userNodeForPackage(DesktopSyncGateway::class.java).node("last_sync")
    private val vaultRoot: Path get() = requireNotNull(vaultRootProvider()) { "Сначала выберите Obsidian Vault" }
    private val stateRoot: Path get() = vaultRoot.resolve(".obsidelta")
    private val ignore = ObsideltaIgnore()

    override fun loadToken(): String? = tokenStore.load()

    override fun saveToken(token: String) = tokenStore.save(token)

    override fun clearToken() = tokenStore.clear()

    override fun loadLastSynchronization(): SynchronizationResult? {
        val completedAt = syncPreferences.get("completed_at", null) ?: return null
        return SynchronizationResult(
            completedAt = completedAt,
            downloadedFiles = syncPreferences.getInt("downloaded", 0),
            uploadedFiles = syncPreferences.getInt("uploaded", 0),
            conflicts = syncPreferences.getInt("backups", 0),
            remoteBundles = syncPreferences.getInt("remote_bundles", 0),
            publishedBundleId = syncPreferences.get("bundle_id", ""),
            message = syncPreferences.get("message", "Синхронизация завершена."),
        )
    }

    override suspend fun listPendingConflicts(): List<SyncConflict> = withContext(Dispatchers.IO) {
        val backupRoot = stateRoot.resolve("backups")
        if (!Files.isDirectory(backupRoot)) return@withContext emptyList()
        Files.list(backupRoot).use { bundles ->
            bundles
                .filter(Files::isDirectory)
                .filter { it.fileName.toString() !in NON_CONFLICT_BACKUP_DIRECTORIES }
                .toList()
                .flatMap { bundleDirectory ->
                    Files.walk(bundleDirectory).use { files ->
                        files.filter(Files::isRegularFile)
                            .map { backup ->
                                val relative = bundleDirectory.relativize(backup).toString().replace('\\', '/')
                                val remote = vaultRoot.resolve(relative).normalize()
                                buildSyncConflict(
                                    bundleId = bundleDirectory.fileName.toString(),
                                    path = relative,
                                    localBytes = Files.readAllBytes(backup),
                                    remoteBytes = remote.takeIf(Files::isRegularFile)?.let(Files::readAllBytes),
                                )
                            }
                            .toList()
                    }
                }
                .sortedWith(compareByDescending<SyncConflict> { it.bundleId }.thenBy { it.path })
                .distinctBy { it.path }
        }
    }

    override suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): ConflictResolutionOutcome = withContext(Dispatchers.IO) {
        val backupRoot = stateRoot.resolve("backups").normalize()
        val backup = backupRoot.resolve(conflict.bundleId).resolve(conflict.path).normalize()
        require(backup.startsWith(backupRoot) && Files.isRegularFile(backup)) {
            "Локальная копия конфликта больше не существует: ${conflict.path}"
        }
        val synchronizedPath = when (resolution) {
            ConflictResolution.KeepRemote -> null
            ConflictResolution.RestoreLocal -> {
                val target = safeVaultPath(conflict.path)
                Files.createDirectories(target.parent)
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING)
                conflict.path
            }
            ConflictResolution.KeepBoth -> {
                val copyPath = uniqueConflictCopyPath(conflict)
                val target = safeVaultPath(copyPath)
                Files.createDirectories(target.parent)
                Files.copy(backup, target)
                copyPath
            }
        }
        deleteConflictBackups(conflict.path, backupRoot)
        ConflictResolutionOutcome(synchronizedPath = synchronizedPath)
    }

    override suspend fun scanVault(): VaultScanResult = withContext(Dispatchers.IO) {
        var total = 0
        var supported = 0
        var ignored = 0
        val files = mutableListOf<VaultFileSnapshot>()
        Files.walk(vaultRoot).use { stream ->
            stream.filter(Files::isRegularFile).forEach { file ->
                val relative = relativePath(file)
                if (relative == ".obsidelta" || relative.startsWith(".obsidelta/")) return@forEach
                total++
                when {
                    ignore.isIgnored(relative) -> ignored++
                    classifyVaultFile(relative) == FileKind.Text -> {
                        val bytes = Files.readAllBytes(file)
                        supported++
                        files += VaultFileSnapshot(
                            path = relative,
                            hash = sha256Hex(bytes),
                            size = bytes.size.toLong(),
                            modifiedAt = Files.getLastModifiedTime(file).toMillis(),
                        )
                    }
                    else -> ignored++
                }
            }
        }
        VaultScanResult(totalFiles = total, supportedFiles = supported, ignoredFiles = ignored, files = files)
    }

    override suspend fun findRemote(token: String): RemoteScanResult = withRemote(token) { remote ->
        val (markers, latest) = coordinator(remote).findRemote()
        RemoteScanResult(
            bundlesCount = markers.size,
            newestBundleId = latest?.bundleId,
            message = if (latest == null) "Облачный журнал пуст — первый пакет создаст это устройство."
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
        val completedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
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
        syncPreferences.put("completed_at", result.completedAt)
        syncPreferences.putInt("downloaded", result.downloadedFiles)
        syncPreferences.putInt("uploaded", result.uploadedFiles)
        syncPreferences.putInt("backups", result.conflicts)
        syncPreferences.putInt("remote_bundles", result.remoteBundles)
        syncPreferences.put("bundle_id", result.publishedBundleId)
        syncPreferences.put("message", result.message)
        syncPreferences.flush()
    }

    private fun coordinator(remote: YandexDiskRestRemoteStorage) = ManualSyncCoordinator(
        remoteStorage = remote,
        remoteRoot = REMOTE_ROOT,
        vaultId = VAULT_ID,
        deviceId = "desktop",
        deviceName = "Windows Desktop",
    )

    private suspend fun buildSnapshot(
        bundleId: String,
        createdAt: String,
        previous: List<VaultFileSnapshot>,
        forceCheckpoint: Boolean,
        onProgress: SyncProgressReporter,
    ): SnapshotPackage {
        val files = supportedFiles()
        val current = files.mapIndexed { index, file ->
            val relative = relativePath(file)
            val bytes = Files.readAllBytes(file)
            onProgress.report(
                SyncStage.BuildingSnapshot,
                "Проверяю: $relative",
                completedItems = index + 1,
                totalItems = files.size,
            )
            VaultFileSnapshot(
                path = relative,
                hash = sha256Hex(bytes),
                size = bytes.size.toLong(),
                modifiedAt = Files.getLastModifiedTime(file).toMillis(),
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
            files.filter { relativePath(it) in changedPaths }.forEach { file ->
                val relative = relativePath(file)
                zip.putNextEntry(ZipEntry("files/$relative"))
                Files.newInputStream(file).use { it.copyTo(zip) }
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
                    contentType = contentType(file.path),
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
                    contentType = contentType(change.path),
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
        var applied = 0
        var conflicts = 0
        var processed = 0
        val totalOperations = manifest.changes.size + manifest.deletes.size
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.startsWith("files/")) {
                    val relative = entry.name.removePrefix("files/").replace('\\', '/')
                    processed++
                    onProgress.report(
                        SyncStage.ApplyingRemote,
                        "Применяю: $relative",
                        completedItems = processed,
                        totalItems = totalOperations,
                    )
                    val target = vaultRoot.resolve(relative).normalize()
                    require(target.startsWith(vaultRoot.normalize())) { "Небезопасный путь в bundle: $relative" }
                    val remoteBytes = zip.readBytes()
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.parent)
                        Files.write(target, remoteBytes, StandardOpenOption.CREATE_NEW)
                        applied++
                    } else {
                        val localBytes = Files.readAllBytes(target)
                        if (!localBytes.contentEquals(remoteBytes)) {
                            backupLocalFile(target, relative, manifest.bundleId)
                            Files.write(target, remoteBytes, StandardOpenOption.TRUNCATE_EXISTING)
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
            val relative = delete.path.replace('\\', '/')
            processed++
            onProgress.report(
                SyncStage.ApplyingRemote,
                "Удаляю: $relative",
                completedItems = processed,
                totalItems = totalOperations,
            )
            val target = vaultRoot.resolve(relative).normalize()
            require(target.startsWith(vaultRoot.normalize())) { "Небезопасный путь удаления: $relative" }
            if (Files.exists(target) && Files.isRegularFile(target)) {
                val localHash = sha256Hex(Files.readAllBytes(target))
                if (localHash != delete.baseHash) {
                    backupLocalFile(target, relative, manifest.bundleId)
                    conflicts++
                }
                Files.delete(target)
                applied++
            }
        }
        if (manifest.checkpoint) {
            val checkpointPaths = manifest.snapshot.mapTo(hashSetOf()) { it.path }
            supportedFiles().filter { relativePath(it) !in checkpointPaths }.forEach { target ->
                val relative = relativePath(target)
                backupLocalFile(target, relative, manifest.bundleId)
                Files.delete(target)
                applied++
                conflicts++
            }
        }
        normalizeLegacyMimeNames()
        return ApplyBundleResult(appliedFiles = applied, conflicts = conflicts)
    }

    private fun supportedFiles(): List<Path> = Files.walk(vaultRoot).use { stream ->
        stream.filter(Files::isRegularFile)
            .filter { file ->
                val relative = relativePath(file)
                !ignore.isIgnored(relative) && classifyVaultFile(relative) == FileKind.Text
            }
            .sorted()
            .toList()
    }

    private fun backupLocalFile(target: Path, relative: String, bundleId: String) {
        val backup = stateRoot.resolve("backups").resolve(bundleId).resolve(relative).normalize()
        require(backup.startsWith(stateRoot.resolve("backups").normalize())) { "Небезопасный backup path: $relative" }
        Files.createDirectories(backup.parent)
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun deleteEmptyBackupParents(start: Path, backupRoot: Path) {
        var directory = start
        while (directory != backupRoot && directory.startsWith(backupRoot)) {
            val isEmpty = Files.list(directory).use { children -> children.findAny().isEmpty }
            if (!isEmpty) return
            Files.delete(directory)
            directory = directory.parent
        }
    }

    private fun deleteConflictBackups(relative: String, backupRoot: Path) {
        Files.list(backupRoot).use { bundles ->
            bundles
                .filter(Files::isDirectory)
                .filter { it.fileName.toString() !in NON_CONFLICT_BACKUP_DIRECTORIES }
                .toList()
                .forEach { bundleDirectory ->
                    val candidate = bundleDirectory.resolve(relative).normalize()
                    require(candidate.startsWith(bundleDirectory.normalize())) { "Небезопасный путь конфликта: $relative" }
                    if (Files.isRegularFile(candidate)) {
                        Files.delete(candidate)
                        deleteEmptyBackupParents(candidate.parent, backupRoot)
                    }
                }
        }
    }

    private fun safeVaultPath(relative: String): Path = vaultRoot.resolve(relative).normalize().also { target ->
        require(target.startsWith(vaultRoot.normalize())) { "Небезопасный путь конфликта: $relative" }
    }

    private fun uniqueConflictCopyPath(conflict: SyncConflict): String {
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
            if (!Files.exists(safeVaultPath(candidate))) return candidate
            index++
        }
    }

    private fun archiveLegacyConflictCopies() {
        val legacyRoot = stateRoot.resolve("backups/legacy-conflict-copies")
        Files.walk(vaultRoot).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.fileName.toString().matches(LEGACY_CONFLICT_FILE) }
                .toList()
                .forEach { file ->
                    val relative = relativePath(file)
                    val archived = legacyRoot.resolve(relative).normalize()
                    Files.createDirectories(archived.parent)
                    Files.move(file, archived, StandardCopyOption.REPLACE_EXISTING)
                }
        }
    }

    private fun normalizeLegacyMimeNames() {
        restoreMissingLegacyMimeFilesOnce()
        val candidates = Files.walk(vaultRoot).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { file -> !ignore.isIgnored(relativePath(file)) }
                .map { file -> file to canonicalMimeName(file.fileName.toString()) }
                .filter { (_, canonicalName) -> canonicalName != null }
                .toList()
        }
        candidates.forEach { (duplicate, canonicalName) ->
            val canonical = duplicate.resolveSibling(requireNotNull(canonicalName))
            if (Files.exists(canonical)) {
                val archived = stateRoot.resolve("backups/legacy-mime-duplicates").resolve(relativePath(duplicate)).normalize()
                Files.createDirectories(archived.parent)
                Files.move(duplicate, archived, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(duplicate, canonical)
            }
        }
    }

    private fun restoreMissingLegacyMimeFilesOnce() {
        val marker = stateRoot.resolve("migrations/mime-name-recovery-v2.done")
        if (Files.exists(marker)) return
        val legacyRoot = stateRoot.resolve("backups/legacy-mime-duplicates")
        if (Files.exists(legacyRoot)) {
            Files.walk(legacyRoot).use { stream ->
                stream.filter(Files::isRegularFile).forEach { backup ->
                    val relative = legacyRoot.relativize(backup)
                    val target = vaultRoot.resolve(relative).normalize()
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.parent)
                        Files.copy(backup, target)
                    }
                }
            }
        }
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "completed", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    private fun relativePath(file: Path): String = vaultRoot.relativize(file).toString().replace('\\', '/')

    private fun isApplied(bundleId: String): Boolean =
        Files.exists(appliedFile) && Files.readAllLines(appliedFile).any { it == bundleId }

    private fun markApplied(bundleId: String) {
        Files.createDirectories(stateRoot)
        if (!isApplied(bundleId)) {
            Files.writeString(
                appliedFile,
                "$bundleId\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun nextSequence(): Int {
        Files.createDirectories(stateRoot)
        val current = if (Files.exists(sequenceFile)) Files.readString(sequenceFile).trim().toIntOrNull() ?: 0 else 0
        val next = current + 1
        Files.writeString(sequenceFile, next.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return next
    }

    private val appliedFile: Path get() = stateRoot.resolve("applied-bundles.txt")
    private val sequenceFile: Path get() = stateRoot.resolve("desktop-sequence.txt")

    override suspend fun checkConnection(token: String): ConnectionResult = withRemote(token) { remote ->
            val result = remote.checkConnection()
            ConnectionResult(
                ok = result.ok,
                message = if (result.ok) "Яндекс Диск подключен." else "Подключение отклонено: ${result.message}",
                freeSpace = result.quota?.freeSpace,
            )
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
}

private val REMOTE_ROOT = RemotePath("disk:/ObsiDeltaSync")
private val VAULT_ID = VaultId("default-vault")
private const val KEY_VAULT_PATH = "vault_path"
private const val DEMO_VAULT_PATH = "C:\\Notes\\DemoVault"
private val LEGACY_CONFLICT_FILE = Regex(".+\\.remote-(desktop|android)-\\d{8}-?\\d{6}(?:-\\d+)?(?:\\.[^.]+)?$")
private val NON_CONFLICT_BACKUP_DIRECTORIES = setOf("legacy-conflict-copies", "legacy-mime-duplicates")

private fun canonicalMimeName(name: String): String? = when {
    name.endsWith(".md.txt", ignoreCase = true) -> name.dropLast(4)
    name.endsWith(".canvas.json", ignoreCase = true) -> name.dropLast(5)
    else -> null
}

private fun contentType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "md" -> "text/markdown"
    "json", "canvas" -> "application/json"
    else -> "text/plain"
}

private fun openYandexLogin() {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI("https://oauth.yandex.ru/"))
}

private fun chooseVaultFolder(): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Выберите папку Obsidian Vault"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
