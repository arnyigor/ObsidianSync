package ru.arny.obsidiansync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.arny.obsidiansync.core.SyncProgress
import ru.arny.obsidiansync.core.SyncStage
import ru.arny.obsidiansync.core.VaultDiff
import ru.arny.obsidiansync.core.VaultFileSnapshot
import ru.arny.obsidiansync.core.diffVaultSnapshots

@Composable
@Preview
fun App(
    vaultPath: String? = null,
    onChooseVaultFolder: (() -> Unit)? = null,
    onOpenYandexLogin: (() -> Unit)? = null,
    syncGateway: SyncPlatformGateway? = null,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DashboardScreen(
                vaultPath = vaultPath,
                onChooseVaultFolder = onChooseVaultFolder,
                onOpenYandexLogin = onOpenYandexLogin,
                syncGateway = syncGateway,
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    vaultPath: String?,
    onChooseVaultFolder: (() -> Unit)?,
    onOpenYandexLogin: (() -> Unit)?,
    syncGateway: SyncPlatformGateway?,
) {
    val scope = rememberCoroutineScope()
    var yandexToken by remember(syncGateway) {
        mutableStateOf(runCatching { syncGateway?.loadToken().orEmpty() }.getOrDefault(""))
    }
    var connection by remember { mutableStateOf<ConnectionResult?>(null) }
    var localScanResult by remember { mutableStateOf<VaultScanResult?>(null) }
    var remoteScanResult by remember { mutableStateOf<RemoteScanResult?>(null) }
    var lastSynchronization by remember(syncGateway) {
        mutableStateOf(runCatching { syncGateway?.loadLastSynchronization() }.getOrNull())
    }
    var pendingConflicts by remember(syncGateway) { mutableStateOf(emptyList<SyncConflict>()) }
    var resolvingConflictId by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf("Выберите Vault и подключите Яндекс Диск.") }
    var isBusy by remember { mutableStateOf(false) }
    var isSynchronizing by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<SyncProgress?>(null) }
    val isConnected = connection?.ok == true
    val vaultDiff = remember(localScanResult, remoteScanResult) {
        val local = localScanResult
        val remote = remoteScanResult
        if (local != null && remote != null) {
            diffVaultSnapshots(previous = remote.files, current = local.files)
        } else {
            null
        }
    }

    fun resolveUserConflict(conflict: SyncConflict, resolution: ConflictResolution) {
        val gateway = syncGateway ?: return
        scope.launch {
            isBusy = true
            resolvingConflictId = conflict.id
            try {
                val outcome = gateway.resolveConflict(conflict, resolution)
                if (resolution != ConflictResolution.KeepRemote) {
                    isSynchronizing = true
                    syncProgress = SyncProgress(SyncStage.Preparing, "Публикую выбранную локальную версию…")
                    val result = gateway.synchronize(yandexToken) { progress ->
                        withContext(Dispatchers.Main.immediate) {
                            syncProgress = progress
                            actionMessage = progress.message
                        }
                    }
                    lastSynchronization = result
                    localScanResult = gateway.scanVault()
                    remoteScanResult = gateway.findRemote(yandexToken)
                    actionMessage = when (resolution) {
                        ConflictResolution.RestoreLocal ->
                            "Локальная версия ${conflict.path} восстановлена и отправлена в облако."
                        ConflictResolution.KeepBoth ->
                            "Обе версии сохранены. Локальная копия: ${outcome.synchronizedPath}."
                        ConflictResolution.KeepRemote -> error("Недостижимое состояние")
                    }
                } else {
                    actionMessage = "Для ${conflict.path} оставлена версия из облака. Локальная резервная копия удалена."
                }
                pendingConflicts = gateway.listPendingConflicts()
            } catch (error: Throwable) {
                val message = error.userMessage("Не удалось разрешить конфликт")
                actionMessage = message
                syncProgress = SyncProgress(SyncStage.Failed, message)
            } finally {
                resolvingConflictId = null
                isSynchronizing = false
                isBusy = false
            }
        }
    }

    LaunchedEffect(vaultPath) {
        localScanResult = null
        if (vaultPath != null) actionMessage = "Vault выбран. Проверьте файлы или запустите синхронизацию."
    }

    LaunchedEffect(vaultPath, syncGateway) {
        pendingConflicts = if (vaultPath != null && syncGateway != null) {
            runCatching { syncGateway.listPendingConflicts() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    LaunchedEffect(syncGateway) {
        if (syncGateway != null && yandexToken.isNotBlank() && connection == null) {
            isBusy = true
            actionMessage = "Проверяю сохранённое подключение…"
            runCatching { syncGateway.checkConnection(yandexToken) }
                .onSuccess {
                    connection = it
                    actionMessage = it.message
                }
                .onFailure { actionMessage = it.userMessage("Ошибка подключения") }
            isBusy = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeContentPadding(),
    ) {
        val compact = maxWidth < 700.dp
        val pagePadding = if (compact) 12.dp else 24.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 1120.dp)
                .verticalScroll(rememberScrollState())
                .padding(pagePadding),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            HeaderCard(
                compact = compact,
                vaultPath = vaultPath,
                isYandexConnected = isConnected,
                freeSpace = connection?.freeSpace,
            )

            AdaptivePair(compact = compact, first = {
                SectionCard(
                    title = "1. Obsidian Vault",
                    subtitle = "Папка заметок на этом устройстве",
                ) {
                    Button(
                        onClick = { onChooseVaultFolder?.invoke() },
                        enabled = onChooseVaultFolder != null && !isBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(if (vaultPath == null) "Выбрать папку Vault" else "Сменить папку Vault")
                    }
                    Text(
                        vaultPath?.let { "Выбрано: ${shortPath(it)}" } ?: "Папка ещё не выбрана",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }, second = {
                SectionCard(
                    title = "2. Яндекс Диск",
                    subtitle = "Токен проверяется реальным запросом к API",
                ) {
                    OutlinedTextField(
                        value = yandexToken,
                        onValueChange = {
                            yandexToken = it.trim()
                            connection = null
                            remoteScanResult = null
                        },
                        label = { Text("OAuth-токен") },
                        placeholder = { Text("Вставьте токен Яндекс Диска") },
                        singleLine = true,
                        enabled = !isConnected && !isBusy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AdaptiveActions(compact = compact, first = {
                        Button(
                            onClick = { onOpenYandexLogin?.invoke() },
                            enabled = onOpenYandexLogin != null && !isBusy && !isConnected,
                            modifier = actionModifier(compact),
                        ) { Text("Получить токен") }
                    }, second = {
                        Button(
                            onClick = {
                                if (isConnected && syncGateway != null) {
                                    syncGateway.clearToken()
                                    yandexToken = ""
                                    connection = null
                                    remoteScanResult = null
                                    syncProgress = null
                                    actionMessage = "Яндекс Диск отключён. Сохранённый токен удалён."
                                } else if (yandexToken.isBlank()) {
                                    actionMessage = "Введите OAuth-токен."
                                } else if (syncGateway != null) {
                                    scope.launch {
                                        isBusy = true
                                        actionMessage = "Проверяю подключение…"
                                        runCatching { syncGateway.checkConnection(yandexToken) }
                                            .onSuccess {
                                                connection = it
                                                if (it.ok) syncGateway.saveToken(yandexToken)
                                                actionMessage = it.message
                                            }
                                            .onFailure { actionMessage = it.userMessage("Ошибка подключения") }
                                        isBusy = false
                                    }
                                }
                            },
                            enabled = syncGateway != null && !isBusy && (isConnected || yandexToken.isNotBlank()),
                            modifier = actionModifier(compact),
                        ) { Text(if (isConnected) "Отключить" else "Подключить") }
                    })
                    Text(
                        if (isConnected) "Статус: подключено" else "Статус: не подключено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })

            SectionCard(
                title = "3. Синхронизация",
                subtitle = "Одна кнопка проверяет Vault и облако, применяет и публикует только реальные изменения",
            ) {
                Button(
                    onClick = {
                        if (syncGateway != null) scope.launch {
                            isBusy = true
                            isSynchronizing = true
                            syncProgress = SyncProgress(SyncStage.Preparing, "Запускаю синхронизацию…")
                            actionMessage = "Синхронизация: скачивание, безопасное применение и отправка…"
                            try {
                                val result = syncGateway.synchronize(yandexToken) { progress ->
                                    withContext(Dispatchers.Main.immediate) {
                                        syncProgress = progress
                                        actionMessage = progress.message
                                    }
                                }
                                lastSynchronization = result
                                localScanResult = syncGateway.scanVault()
                                remoteScanResult = syncGateway.findRemote(yandexToken)
                                pendingConflicts = syncGateway.listPendingConflicts()
                                actionMessage = result.message
                            } catch (error: Throwable) {
                                val message = error.userMessage("Ошибка синхронизации")
                                actionMessage = message
                                syncProgress = SyncProgress(SyncStage.Failed, message)
                            } finally {
                                isBusy = false
                                isSynchronizing = false
                            }
                        }
                    },
                    enabled = vaultPath != null && isConnected && syncGateway != null && !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) {
                    Text(
                        if (isSynchronizing) "Проверка и синхронизация…" else "Проверить и синхронизировать",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                syncProgress?.let { SyncProgressPanel(it) }
            }

            if (pendingConflicts.isNotEmpty()) {
                ConflictResolutionPanel(
                    compact = compact,
                    conflicts = pendingConflicts,
                    resolvingConflictId = resolvingConflictId,
                    enabled = isConnected && !isBusy,
                    onResolve = ::resolveUserConflict,
                )
            }

            AdaptivePair(compact = compact, first = {
                ResultCard(
                    title = "На устройстве",
                    text = localScanResult?.let { localScanText(it, vaultDiff) }
                        ?: "Запустите синхронизацию для проверки Vault.",
                )
            }, second = {
                ResultCard(
                    title = "В облаке",
                    text = remoteScanResult?.let { remoteScanText(it, vaultDiff) }
                        ?: "Подключите Яндекс Диск и запустите синхронизацию.",
                )
            })

            SynchronizationSummary(
                compact = compact,
                isSynchronizing = isSynchronizing,
                result = lastSynchronization,
            )

            SectionCard(title = "Статус", subtitle = "Последнее выполненное действие") {
                EmptyState(actionMessage)
            }

            SectionCard(title = "Безопасность", subtitle = "Конфликты не перезаписываются молча") {
                Bullet("При конфликте локальная версия сохраняется, а приложение предлагает выбрать локальный или облачный файл.")
                Bullet("Служебная папка .obsidelta исключается из пакетов синхронизации.")
                Bullet("Размер и SHA-256 скачанного пакета проверяются до применения.")
                Bullet("Токен скрыт в интерфейсе и не попадает в диагностические сообщения.")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeaderCard(
    compact: Boolean,
    vaultPath: String?,
    isYandexConnected: Boolean,
    freeSpace: Long?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(if (compact) 16.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("ObsiDelta Sync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Ручная синхронизация Obsidian через Яндекс Диск")
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Vault: ${vaultPath?.let(::shortPath) ?: "не выбран"}")
                    StatusPill("Облако: ${if (isYandexConnected) "подключено" else "не подключено"}")
                    StatusPill("Свободно: ${freeSpace?.let(::formatBytes) ?: "—"}")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Vault: ${vaultPath?.let(::shortPath) ?: "не выбран"}", Modifier.weight(1.4f))
                    StatusPill("Облако: ${if (isYandexConnected) "подключено" else "не подключено"}", Modifier.weight(1f))
                    StatusPill("Свободно: ${freeSpace?.let(::formatBytes) ?: "—"}", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AdaptivePair(
    compact: Boolean,
    first: @Composable ColumnScope.() -> Unit,
    second: @Composable ColumnScope.() -> Unit,
) {
    if (compact) {
        first()
        second()
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f), content = first)
            Column(modifier = Modifier.weight(1f), content = second)
        }
    }
}

@Composable
private fun AdaptiveActions(
    compact: Boolean,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first()
            second()
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) { first() }
            Box(modifier = Modifier.weight(1f)) { second() }
        }
    }
}

private fun actionModifier(compact: Boolean): Modifier = Modifier
    .fillMaxWidth()
    .heightIn(min = if (compact) 48.dp else 44.dp)

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ResultCard(title: String, text: String) {
    SectionCard(title = title, subtitle = "Текущее состояние") { EmptyState(text) }
}

@Composable
private fun ConflictResolutionPanel(
    compact: Boolean,
    conflicts: List<SyncConflict>,
    resolvingConflictId: String?,
    enabled: Boolean,
    onResolve: (SyncConflict, ConflictResolution) -> Unit,
) {
    SectionCard(
        title = "Нужно выбрать версию",
        subtitle = "Облачная версия уже находится в Vault. Ваша прежняя локальная версия сохранена и не потеряна.",
    ) {
        conflicts.forEach { conflict ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(conflict.path, fontWeight = FontWeight.SemiBold)
                    Text(conflict.changeSummary, style = MaterialTheme.typography.bodySmall)
                    ConflictVersionComparison(compact = compact, conflict = conflict)
                    Text(
                        "Оставьте файл, полученный из облака, или верните вашу локальную копию. " +
                            "Можно также сохранить обе версии рядом и удалить ненужную позже.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ConflictActions(compact = compact, keepRemote = {
                        OutlinedButton(
                            onClick = { onResolve(conflict, ConflictResolution.KeepRemote) },
                            enabled = enabled && resolvingConflictId != conflict.id,
                            modifier = actionModifier(compact),
                        ) { Text("Оставить облачную") }
                    }, keepBoth = {
                        OutlinedButton(
                            onClick = { onResolve(conflict, ConflictResolution.KeepBoth) },
                            enabled = enabled && resolvingConflictId != conflict.id,
                            modifier = actionModifier(compact),
                        ) { Text("Сохранить обе рядом") }
                    }, restoreLocal = {
                        Button(
                            onClick = { onResolve(conflict, ConflictResolution.RestoreLocal) },
                            enabled = enabled && resolvingConflictId != conflict.id,
                            modifier = actionModifier(compact),
                        ) { Text("Вернуть локальную") }
                    })
                }
            }
        }
    }
}

@Composable
private fun ConflictVersionComparison(compact: Boolean, conflict: SyncConflict) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ConflictVersion("Ваша локальная версия", conflict.localExcerpt)
            ConflictVersion("Версия из облака", conflict.remoteExcerpt)
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { ConflictVersion("Ваша локальная версия", conflict.localExcerpt) }
            Box(Modifier.weight(1f)) { ConflictVersion("Версия из облака", conflict.remoteExcerpt) }
        }
    }
}

@Composable
private fun ConflictVersion(title: String, excerpt: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(excerpt, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConflictActions(
    compact: Boolean,
    keepRemote: @Composable () -> Unit,
    keepBoth: @Composable () -> Unit,
    restoreLocal: @Composable () -> Unit,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            keepRemote()
            keepBoth()
            restoreLocal()
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { keepRemote() }
            Box(Modifier.weight(1f)) { keepBoth() }
            Box(Modifier.weight(1f)) { restoreLocal() }
        }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 2)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SyncProgressPanel(progress: SyncProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(syncStageLabel(progress.stage), fontWeight = FontWeight.SemiBold)
            Text("${(progress.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(progress.message, style = MaterialTheme.typography.bodyMedium)
        if (progress.completedItems != null && progress.totalItems != null) {
            Text(
                "Файлов: ${progress.completedItems} из ${progress.totalItems}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun syncStageLabel(stage: SyncStage): String = when (stage) {
    SyncStage.Completed -> "Завершено"
    SyncStage.Failed -> "Ошибка"
    else -> "Этап ${stage.step} из ${SyncProgress.TOTAL_STEPS}"
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•")
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SynchronizationSummary(
    compact: Boolean,
    isSynchronizing: Boolean,
    result: SynchronizationResult?,
) {
    SectionCard(
        title = "Последняя синхронизация",
        subtitle = when {
            isSynchronizing -> "Выполняется сейчас — дождитесь итогового отчёта"
            result == null -> "Синхронизация ещё не запускалась"
            else -> "Завершена ${formatSyncTime(result.completedAt)}"
        },
    ) {
        when {
            isSynchronizing -> EmptyState(
                "Получаю облачный пакет → применяю файлы → создаю и отправляю новый snapshot.",
            )
            result == null -> EmptyState("После первого запуска здесь появятся точное время и итоговые числа.")
            compact -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncMetric("Применено изменений", result.downloadedFiles.toString())
                SyncMetric("Опубликовано изменений", result.uploadedFiles.toString())
                SyncMetric("Найдено конфликтов", result.conflicts.toString())
                SyncMetric("Пакетов в облаке", result.remoteBundles.toString())
                Text("Пакет: ${result.publishedBundleId}", style = MaterialTheme.typography.bodySmall)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { SyncMetric("Применено изменений", result.downloadedFiles.toString()) }
                    Box(Modifier.weight(1f)) { SyncMetric("Опубликовано изменений", result.uploadedFiles.toString()) }
                    Box(Modifier.weight(1f)) { SyncMetric("Найдено конфликтов", result.conflicts.toString()) }
                    Box(Modifier.weight(1f)) { SyncMetric("Пакетов в облаке", result.remoteBundles.toString()) }
                }
                Text("Опубликован: ${result.publishedBundleId}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SyncMetric(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

data class VaultScanResult(
    val totalFiles: Int,
    val supportedFiles: Int,
    val ignoredFiles: Int,
    val files: List<VaultFileSnapshot> = emptyList(),
)

data class ConnectionResult(
    val ok: Boolean,
    val message: String,
    val freeSpace: Long? = null,
)

data class RemoteScanResult(
    val bundlesCount: Int,
    val newestBundleId: String?,
    val message: String,
    val files: List<VaultFileSnapshot> = emptyList(),
)

private fun localScanText(result: VaultScanResult, diff: VaultDiff?): String = buildString {
    append("Поддерживаемых: ${result.supportedFiles}\nВсего файлов: ${result.totalFiles}\nИгнорируется: ${result.ignoredFiles}")
    if (diff != null) {
        append("\n\nНет в облаке: ${diff.created.size}\nИзменены локально: ${diff.modified.size}")
        appendPaths("Локальные", diff.created.map { it.path })
        appendPaths("Изменённые", diff.modified.map { it.path })
    }
}

private fun remoteScanText(result: RemoteScanResult, diff: VaultDiff?): String = buildString {
    append("Пакетов: ${result.bundlesCount}\nПоследний: ${result.newestBundleId ?: "нет"}")
    if (diff != null) {
        append("\n\nНет в облаке: ${diff.created.size}\nНет локально: ${diff.deleted.size}\nРазличаются: ${diff.modified.size}")
        appendPaths("Только в облаке", diff.deleted.map { it.path })
    }
}

private fun StringBuilder.appendPaths(label: String, paths: List<String>) {
    if (paths.isEmpty()) return
    append("\n$label: ")
    append(paths.take(5).joinToString())
    if (paths.size > 5) append(" и ещё ${paths.size - 5}")
}

data class SynchronizationResult(
    val completedAt: String,
    val downloadedFiles: Int,
    val uploadedFiles: Int,
    val conflicts: Int,
    val remoteBundles: Int,
    val publishedBundleId: String,
    val message: String,
)

private fun Throwable.userMessage(prefix: String): String = "$prefix: ${message ?: "неизвестная ошибка"}"

private fun formatSyncTime(value: String): String = value.removeSuffix("Z").replace('T', ' ')

private fun shortPath(path: String): String =
    if (path.length <= 38) path else "…" + path.takeLast(37)

private fun formatBytes(bytes: Long): String {
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes ${units[unit]}" else "${(value * 10).toInt() / 10.0} ${units[unit]}"
}
