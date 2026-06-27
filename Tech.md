# Техническое задание v2.0

# KMP Compose синхронизатор Obsidian Vault через Yandex Disk REST API

## 0. Статус документа

Версия: **2.0**

Дата актуализации: **2026-06-28**

Основание для обновления: успешно проверена работа Yandex Disk REST API на реальном round-trip тесте:

```text
API base:
  https://cloud-api.yandex.net/v1/disk

Remote root:
  disk:/ObsidianSyncTest

Проверено:
  создание remote-папки
  загрузка 10 .md файлов
  получение списка файлов
  скачивание файлов обратно
  сверка SHA-256

Результат:
  RESULT: OK - upload/download/hash verification passed
```

Вывод: для MVP основным remote backend становится **Yandex Disk REST API**. WebDAV больше не является основным backend-ом MVP.

---

# 1. Название проекта

Рабочее название:

```text
Vault Delta Sync Manager
```

Короткое название:

```text
ObsiDeltaSync
```

Назначение:

```text
KMP Compose приложение для ручной и полуавтоматической синхронизации Obsidian vault между Desktop и Android через пакетные delta-архивы, manifest-файлы и remote storage.
```

---

# 2. Главная идея проекта

Не делать аналог Syncthing.

Не делать Git-плагин.

Не делать постоянное зеркалирование папки.

Сделать **ручной контролируемый синхронизатор**, который работает по модели:

```text
Пользователь изменил файлы в Obsidian
→ открыл синхронизатор
→ увидел список локальных изменений
→ выбрал конкретные файлы
→ нажал Push
→ приложение создало bundle с изменениями
→ загрузило bundle и manifest на Yandex Disk
→ на другом устройстве пользователь нажал Fetch/Pull
→ увидел remote-изменения
→ применил безопасные изменения
→ конфликты решил вручную через UI
```

Главная ценность приложения:

```text
прозрачность
контроль
ручной выбор файлов
отсутствие молчаливой перезаписи
работа через личное облако
бесплатный/дешевый backend
расширяемая архитектура remote storage
```

---

# 3. Ключевое архитектурное решение

## 3.1. Remote — не “текущая копия vault”

Remote storage не должен хранить один файл вида:

```text
latest.zip
latest_manifest.json
```

Такой подход запрещен, потому что приводит к перезаписи состояния и потере изменений.

Правильная модель:

```text
remote = журнал immutable bundles
```

То есть каждый пакет изменений создается один раз и не изменяется.

Пример:

```text
desktop_000001.bundle.enc
android_000001.bundle.enc
desktop_000002.bundle.enc
android_resolution_000003.bundle.enc
```

---

## 3.2. Синхронизация через delta bundles

Каждый bundle содержит только выбранные измененные файлы.

Пример:

```text
Изменились:
  Inbox.md
  Daily/2026-06-28.md

Создается:
  desktop_000014_20260628T010000Z.bundle.enc
  desktop_000014_20260628T010000Z.manifest.json
```

Bundle не обязан содержать весь vault.

Bundle содержит только delta:

```text
created files
modified files
deleted-file tombstones
optional conflict resolution result
```

---

## 3.3. Manifest — основной источник истины для анализа

Manifest должен позволять приложению понять:

```text
какой файл изменился
кто изменил
когда изменил
какая была базовая версия
какая стала новая версия
можно ли применить изменение автоматически
есть ли конфликт
какой bundle нужно скачать
какой hash должен быть у bundle
```

Manifest скачивается раньше bundle.

Это важно:

```text
manifest маленький
bundle может быть большим
приложение сначала показывает пользователю список изменений
и только потом скачивает нужные bundles
```

---

# 4. Поддерживаемые платформы

## 4.1. MVP

Обязательные платформы:

```text
Windows Desktop
Android
```

## 4.2. После MVP

Желательные платформы:

```text
Linux Desktop
macOS Desktop
```

## 4.3. Не входит в MVP

```text
iOS
Web
Browser extension
Obsidian plugin
```

Причина исключения iOS: сложный доступ к файловой системе и интеграции с Obsidian vault.

---

# 5. Технологический стек

## 5.1. Базовый стек

```text
Kotlin Multiplatform
Compose Multiplatform
Kotlinx Coroutines
Kotlinx Serialization
Ktor Client
Okio
SQLite через SQLDelight или другой KMP-compatible слой
```

## 5.2. Desktop

```text
Compose Desktop
JVM target
Java NIO / Okio FileSystem
jpackage или Compose Desktop Gradle packaging
```

## 5.3. Android

```text
Compose Multiplatform / Jetpack Compose
Storage Access Framework
ACTION_OPEN_DOCUMENT_TREE
takePersistableUriPermission
Android Keystore
Ktor Client
```

## 5.4. Remote MVP

```text
Yandex Disk REST API
API base: https://cloud-api.yandex.net/v1/disk
```

## 5.5. Archive

Для MVP:

```text
ZIP
```

После MVP:

```text
tar.zst
```

Причина:

```text
ZIP проще отлаживать
tar.zst лучше сжимает Markdown и JSON
```

## 5.6. Hash

MVP:

```text
SHA-256
```

После MVP:

```text
BLAKE3 как опция
```

Причина:

```text
SHA-256 уже проверен в тестовом скрипте
SHA-256 стандартен и доступен на всех платформах
BLAKE3 быстрее, но усложняет KMP-зависимости
```

## 5.7. Encryption

MVP допускает два режима:

```text
encryption disabled для локального тестирования
encryption enabled для реального remote
```

Целевая схема:

```text
AES-GCM или XChaCha20-Poly1305
passphrase-derived key
encrypted bundle
optional encrypted manifest после MVP
```

---

# 6. Подтвержденные данные по Yandex Disk REST API

По результатам локального теста подтверждено:

```text
1. OAuth token работает.
2. API endpoint https://cloud-api.yandex.net/v1/disk доступен.
3. Remote path формата disk:/ObsidianSyncTest работает.
4. Можно создать remote directory.
5. Можно загрузить .md файлы.
6. Можно получить listing remote directory.
7. Можно скачать файлы обратно.
8. SHA-256 исходных и скачанных файлов совпадает.
```

Фактический результат теста:

```text
Files: 10
Uploaded: 10
Downloaded: 10
Verified: 10
Result: OK
```

Также из теста видно:

```text
total_space = 45097156608 bytes
used_space  = 39948979819 bytes
```

Следовательно, в MVP нужно показывать пользователю свободное место и предупреждать, если места мало.

---

# 7. Выбор remote backend-ов

## 7.1. MVP backend

Основной MVP backend:

```text
Yandex Disk REST API
```

Причина:

```text
уже протестирован
доступен из России
имеет OAuth
работает через HTTPS
подходит для Desktop и Android
поддерживает app-folder и full-disk сценарии
```

---

## 7.2. Secondary backend после MVP

```text
Yandex Disk WebDAV
```

Статус:

```text
fallback
не основной backend
```

Причина:

```text
REST API уже работает
REST API лучше контролируется из приложения
WebDAV можно оставить для совместимости и резервного варианта
```

---

## 7.3. Backends после MVP

```text
GitHub private repo / GitHub Releases
Generic WebDAV
S3-compatible storage
Local Folder remote
SFTP
```

Приоритет после MVP:

```text
1. LocalFolderRemoteStorage для тестов
2. YandexDiskRestRemoteStorage
3. GenericWebDavRemoteStorage
4. S3RemoteStorage
5. GitHubRemoteStorage
```

---

# 8. Yandex Disk REST API backend

## 8.1. Название backend-а

```text
YandexDiskRestRemoteStorage
```

## 8.2. Базовый endpoint

```text
https://cloud-api.yandex.net/v1/disk
```

## 8.3. Поддерживаемые root paths

Должны поддерживаться два режима.

### Full disk mode

```text
disk:/ObsiDeltaSync
disk:/ObsidianSyncTest
disk:/Apps/ObsiDeltaSync
```

Требует прав на чтение/запись в Яндекс.Диск.

### App folder mode

```text
app:/ObsiDeltaSync
```

Требует права только на папку приложения.

---

## 8.4. Рекомендуемый MVP root

Для разработки и тестов:

```text
disk:/ObsidianSyncTest
```

Для реального MVP:

```text
app:/ObsiDeltaSync
```

или:

```text
disk:/Apps/ObsiDeltaSync
```

Практическое решение:

```text
В настройках приложения дать выбор:
  - app folder
  - custom disk path
```

---

## 8.5. Авторизация

MVP должен поддерживать ручной ввод OAuth token.

```text
Settings → Remote → Yandex Disk → OAuth Token
```

Токен хранить:

```text
Android: Android Keystore / EncryptedSharedPreferences
Desktop: encrypted config или OS credential storage
```

После MVP добавить нормальный OAuth flow:

```text
Open browser
User authorizes app
Redirect / verification code
App stores token securely
```

---

## 8.6. Требуемые permissions

Для режима `disk:/...`:

```text
read disk
write disk
```

Для режима `app:/...`:

```text
app folder access
```

Приложение должно явно показывать пользователю, какой режим используется.

---

## 8.7. Операции Yandex backend-а

Backend должен реализовать общий интерфейс `RemoteStorage`.

Минимальные операции:

```text
checkConnection()
getDiskInfo()
createDirectory(path)
listDirectory(path)
getResource(path)
uploadFile(localBytes, remotePath, overwrite)
downloadFile(remotePath)
deleteFile(remotePath)
moveFile(from, to, overwrite)
exists(path)
```

Высокоуровневые операции:

```text
listRemoteManifests(vaultId)
uploadManifest(manifest)
downloadManifest(manifestRef)
uploadBundle(bundle)
downloadBundle(bundleRef)
publishReadyMarker(bundleId)
cleanupTmp()
```

---

# 9. Remote directory layout для Yandex Disk

## 9.1. Общая структура

```text
{remote_root}/
  vaults/
    {vault_id}/
      meta/
        vault.json
        devices.json

      devices/
        {device_id}.json

      manifests/
        2026/
          06/
            desktop_000001_20260628T010000Z.manifest.json
            android_000001_20260628T011000Z.manifest.json

      bundles/
        2026/
          06/
            desktop_000001_20260628T010000Z.bundle.enc
            android_000001_20260628T011000Z.bundle.enc

      ready/
        desktop_000001_20260628T010000Z.ready
        android_000001_20260628T011000Z.ready

      tmp/
        uploads/

      checkpoints/
        checkpoint_000100.json

      tombstones/
        tombstones_2026_06.jsonl
```

---

## 9.2. Почему нужен ready marker

Yandex Disk REST upload может быть двухэтапным:

```text
получить upload URL
загрузить файл на upload URL
```

Если другое устройство начнет читать remote во время upload, возможны промежуточные состояния.

Поэтому приложение должно считать bundle готовым только если есть:

```text
ready/{bundle_id}.ready
```

Или если manifest имеет статус:

```json
{
  "status": "ready"
}
```

В MVP надежнее использовать отдельный ready marker.

---

# 10. Upload protocol

## 10.1. Цель

Не допустить ситуации, когда другое устройство скачивает недозагруженный bundle.

## 10.2. Алгоритм загрузки bundle

```text
1. Создать local bundle.
2. Посчитать SHA-256 bundle.
3. Создать manifest.
4. Загрузить bundle в:
   tmp/uploads/{bundle_id}.bundle.enc.tmp

5. Загрузить manifest в:
   tmp/uploads/{bundle_id}.manifest.json.tmp

6. Проверить, что remote-файлы существуют и размер совпадает.

7. Переместить bundle:
   tmp/uploads/{bundle_id}.bundle.enc.tmp
   →
   bundles/YYYY/MM/{bundle_id}.bundle.enc

8. Переместить manifest:
   tmp/uploads/{bundle_id}.manifest.json.tmp
   →
   manifests/YYYY/MM/{bundle_id}.manifest.json

9. Создать ready marker:
   ready/{bundle_id}.ready

10. Обновить local DB:
    bundle status = pushed
```

---

## 10.3. Если move недоступен или нестабилен

Допускается fallback:

```text
загрузить сразу в final path
но ready marker создать только после полной загрузки и проверки
```

Pull-клиент обязан игнорировать bundles без ready marker.

---

## 10.4. Idempotency

Повторный upload того же `bundle_id` запрещен.

Если remote уже содержит `ready/{bundle_id}.ready`, повторная загрузка должна остановиться с ошибкой:

```text
BundleAlreadyExists
```

---

# 11. Pull protocol

## 11.1. Fetch remote changes

Приложение не должно сразу скачивать bundles.

Сначала:

```text
1. Скачать список ready markers.
2. Скачать только manifests для новых ready bundles.
3. Отфильтровать уже примененные bundle_id.
4. Показать пользователю remote changes.
```

---

## 11.2. Download and apply

```text
1. Пользователь выбирает изменения.
2. Приложение скачивает bundle.
3. Проверяет SHA-256 bundle.
4. Расшифровывает bundle.
5. Распаковывает в staging.
6. Проверяет hash каждого файла.
7. Сравнивает local_hash с base_hash.
8. Безопасные изменения применяет.
9. Конфликты отправляет в conflict resolver.
10. Обновляет local DB.
11. Записывает applied_bundles.
```

---

# 12. Local vault requirements

## 12.1. Desktop

Desktop работает с обычной папкой:

```text
D:\Notes\Vault
```

или любой выбранной пользователем директорией.

Требования:

```text
read access
write access
ability to create .obsidelta/
```

---

## 12.2. Android

Android работает через Storage Access Framework.

Поддерживается только Obsidian vault, созданный в:

```text
Device storage
```

Не поддерживается в MVP:

```text
Obsidian app storage
```

Первый запуск Android:

```text
1. Пользователь выбирает vault folder через ACTION_OPEN_DOCUMENT_TREE.
2. Приложение сохраняет persistable URI permission.
3. Приложение проверяет чтение/запись.
4. Приложение проверяет наличие .obsidian/.
5. Приложение создает тестовый файл и удаляет его.
```

---

# 13. Local state

## 13.1. Где хранить state

MVP:

```text
{vault}/.obsidelta/state.db
```

Дополнительно:

```text
{vault}/.obsidelta/backups/
{vault}/.obsidelta/staging/
{vault}/.obsidelta/logs/
{vault}/.obsidelta/cache/
```

Папка `.obsidelta/` должна быть исключена из пользовательской синхронизации.

---

## 13.2. SQLite tables

```sql
CREATE TABLE vault_info (
    vault_id TEXT PRIMARY KEY,
    vault_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    format_version INTEGER NOT NULL,
    remote_backend TEXT NOT NULL,
    remote_root TEXT NOT NULL
);

CREATE TABLE devices (
    device_id TEXT PRIMARY KEY,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE files (
    path TEXT PRIMARY KEY,
    current_hash TEXT,
    size INTEGER NOT NULL,
    modified_at INTEGER,
    deleted INTEGER NOT NULL DEFAULT 0,
    last_scan_at TEXT,
    last_seen_bundle_id TEXT
);

CREATE TABLE file_versions (
    id TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    hash TEXT NOT NULL,
    size INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    source_device_id TEXT,
    bundle_id TEXT,
    object_local_path TEXT,
    is_base_snapshot INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE bundles (
    bundle_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    direction TEXT NOT NULL,
    status TEXT NOT NULL,
    manifest_hash TEXT,
    bundle_hash TEXT,
    remote_manifest_path TEXT,
    remote_bundle_path TEXT,
    applied_at TEXT
);

CREATE TABLE applied_bundles (
    bundle_id TEXT PRIMARY KEY,
    applied_at TEXT NOT NULL,
    source_device_id TEXT NOT NULL,
    status TEXT NOT NULL
);

CREATE TABLE conflicts (
    conflict_id TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    base_hash TEXT,
    local_hash TEXT,
    remote_hash TEXT,
    bundle_id TEXT NOT NULL,
    status TEXT NOT NULL,
    resolution TEXT,
    created_at TEXT NOT NULL,
    resolved_at TEXT
);

CREATE TABLE tombstones (
    id TEXT PRIMARY KEY,
    path TEXT NOT NULL,
    deleted_hash TEXT,
    deleted_at TEXT NOT NULL,
    source_device_id TEXT NOT NULL,
    bundle_id TEXT NOT NULL
);

CREATE TABLE remote_cache (
    remote_id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    path TEXT NOT NULL,
    hash TEXT,
    size INTEGER,
    created_at TEXT,
    fetched_at TEXT
);
```

---

# 14. Manifest format v2

## 14.1. Manifest example

```json
{
  "format_version": 2,
  "protocol": "obsidelta",
  "vault_id": "main-vault-id",
  "bundle_id": "desktop_000001_20260628T010000Z",
  "device_id": "desktop",
  "device_name": "Windows PC",
  "created_at": "2026-06-28T01:00:00Z",
  "sequence": 1,
  "status": "ready",
  "remote": {
    "backend": "yandex_disk_rest",
    "root": "disk:/ObsiDeltaSync",
    "manifest_path": "disk:/ObsiDeltaSync/vaults/main-vault-id/manifests/2026/06/desktop_000001_20260628T010000Z.manifest.json",
    "bundle_path": "disk:/ObsiDeltaSync/vaults/main-vault-id/bundles/2026/06/desktop_000001_20260628T010000Z.bundle.enc",
    "ready_marker_path": "disk:/ObsiDeltaSync/vaults/main-vault-id/ready/desktop_000001_20260628T010000Z.ready"
  },
  "bundle": {
    "file_name": "desktop_000001_20260628T010000Z.bundle.enc",
    "size": 12345,
    "hash_algorithm": "sha256",
    "hash": "bundle-sha256",
    "archive": "zip",
    "encrypted": true,
    "encryption": {
      "algorithm": "AES-GCM",
      "key_id": "default"
    }
  },
  "changes": [
    {
      "path": "Inbox.md",
      "operation": "modify",
      "base_hash": "hash-before-change",
      "new_hash": "hash-after-change",
      "size": 2048,
      "modified_at": 1782552000,
      "content_type": "text/markdown",
      "file_kind": "text"
    },
    {
      "path": "NewNote.md",
      "operation": "create",
      "base_hash": null,
      "new_hash": "hash-new-file",
      "size": 1024,
      "modified_at": 1782552001,
      "content_type": "text/markdown",
      "file_kind": "text"
    }
  ],
  "deletes": [
    {
      "path": "Old.md",
      "operation": "delete",
      "base_hash": "hash-before-delete",
      "deleted_at": "2026-06-28T01:00:00Z",
      "file_kind": "text"
    }
  ],
  "renames": [],
  "stats": {
    "created": 1,
    "modified": 1,
    "deleted": 1,
    "total_files": 3,
    "total_uncompressed_size": 3072
  }
}
```

---

## 14.2. Ключевое поле `base_hash`

Для операции `modify`:

```text
base_hash = hash файла до изменения
new_hash = hash файла после изменения
```

Для операции `create`:

```text
base_hash = null
new_hash = hash нового файла
```

Для операции `delete`:

```text
base_hash = hash удаляемого файла
new_hash = null
```

`base_hash` нужен для обнаружения конфликтов.

---

# 15. Bundle format v2

## 15.1. До шифрования

```text
bundle.zip
  manifest.json
  files/
    Inbox.md
    Daily/2026-06-28.md
    NewNote.md
  deletes.json
```

## 15.2. После шифрования

```text
desktop_000001_20260628T010000Z.bundle.enc
```

В remote хранится только encrypted bundle.

---

# 16. Сканирование vault

## 16.1. Ignore rules

Поддержать файл:

```text
.obsideltaignore
```

Стартовые правила:

```gitignore
.obsidelta/
.git/
.trash/

.DS_Store
Thumbs.db
desktop.ini

*.tmp
*.temp
*.bak
*.swp
*.part
*.crdownload

.obsidian/workspace.json
.obsidian/workspace-mobile.json
.obsidian/workspace-desktop.json

.embeddings/
.llm-cache/
.rag-cache/
```

---

## 16.2. Типы файлов MVP

Автоматически включать:

```text
.md
.txt
.json
.canvas
```

Показывать отдельно и требовать ручного выбора:

```text
.png
.jpg
.jpeg
.webp
.gif
.pdf
```

По умолчанию блокировать или предупреждать:

```text
.mp4
.zip
.7z
.rar
.exe
.bin
.gguf
```

---

# 17. Алгоритм Push

```text
1. Загрузить local state из SQLite.
2. Просканировать vault.
3. Применить .obsideltaignore.
4. Посчитать SHA-256 для новых/измененных файлов.
5. Сравнить snapshot с таблицей files.
6. Сформировать список:
   - created
   - modified
   - deleted
   - media changes
   - ignored
7. Показать пользователю список.
8. Пользователь выбирает файлы.
9. Создать manifest v2.
10. Создать staging directory.
11. Скопировать выбранные файлы в staging.
12. Создать zip bundle.
13. Проверить hashes файлов внутри bundle.
14. Зашифровать bundle, если включено шифрование.
15. Посчитать SHA-256 encrypted bundle.
16. Через Yandex REST API загрузить bundle в tmp path.
17. Через Yandex REST API загрузить manifest в tmp path.
18. Проверить remote metadata.
19. Переместить tmp files в final paths или создать ready marker.
20. Обновить local DB.
21. Показать результат.
```

---

# 18. Алгоритм Fetch Remote

```text
1. Получить список ready markers.
2. Для каждого неизвестного bundle_id найти manifest.
3. Скачать manifest.
4. Проверить format_version.
5. Проверить vault_id.
6. Отфильтровать уже applied bundles.
7. Отфильтровать собственные bundles, если они уже известны локально.
8. Показать список remote changes:
   - source device
   - created at
   - changed files
   - deleted files
   - estimated conflicts
   - bundle size
```

---

# 19. Алгоритм Pull/Apply

```text
1. Пользователь выбирает remote changes.
2. Скачать нужный encrypted bundle.
3. Проверить SHA-256 encrypted bundle.
4. Расшифровать bundle.
5. Распаковать в staging.
6. Проверить manifest внутри bundle.
7. Проверить hash каждого файла.
8. Для каждого изменения:
   - если local_hash == base_hash → safe apply
   - если local_hash != base_hash → conflict
   - если файл отсутствует неожиданно → conflict или special case
9. Перед safe apply создать backup.
10. Применить safe changes.
11. Конфликты записать в SQLite.
12. Показать conflict resolver.
13. Записать applied_bundles только после успешного применения.
```

---

# 20. Conflict resolver

## 20.1. Типы конфликтов

```text
modify-vs-modify
delete-vs-modify
create-vs-create-same-path
binary-vs-binary
settings-file-conflict
missing-base
hash-mismatch
```

---

## 20.2. Действия для Markdown/text

MVP обязан поддерживать:

```text
Keep Local
Keep Remote
Save Both
Append Remote to End
Append Remote to Beginning
Manual Merge
```

После MVP:

```text
Auto Three-Way Merge
Markdown-aware Merge
```

---

## 20.3. Действия для media/binary

```text
Keep Local
Keep Remote
Save Both
```

Merge для binary не делать.

---

## 20.4. Save Both naming

Пример:

```text
Inbox.md
Inbox.remote-conflict.desktop.20260628T010000.md
```

или:

```text
Inbox.local-conflict.android.20260628T010000.md
```

---

## 20.5. Resolution event

После разрешения конфликта приложение должно создать resolution manifest/bundle.

Пример:

```json
{
  "format_version": 2,
  "protocol": "obsidelta",
  "type": "conflict_resolution",
  "vault_id": "main-vault-id",
  "bundle_id": "android_resolution_000003_20260628T012000Z",
  "device_id": "android",
  "conflict_id": "conflict-uuid",
  "path": "Inbox.md",
  "resolution": "save_both",
  "result_hash": "hash-result",
  "created_at": "2026-06-28T01:20:00Z"
}
```

---

# 21. Backup before apply

Перед изменением любого локального файла обязательно создать backup.

Структура:

```text
.obsidelta/
  backups/
    2026-06-28T01-20-00_before_desktop_000001/
      Inbox.md
      Daily/2026-06-28.md
      backup_manifest.json
```

Backup manifest:

```json
{
  "backup_id": "2026-06-28T01-20-00_before_desktop_000001",
  "reason": "before_apply_bundle",
  "bundle_id": "desktop_000001_20260628T010000Z",
  "created_at": "2026-06-28T01:20:00Z",
  "files": [
    {
      "path": "Inbox.md",
      "hash": "old-local-hash"
    }
  ]
}
```

---

# 22. История и retention

## 22.1. Обязательная техническая история

MVP обязан хранить:

```text
applied bundles
pushed bundles
base hashes
file versions для base snapshots
tombstones
conflicts
resolution events
backup metadata
```

---

## 22.2. Пользовательская история версий

Не обязательна для MVP.

Но архитектура должна позволять позже добавить:

```text
File History screen
Restore previous version
Compare versions
```

---

## 22.3. Retention policy

Настройки по умолчанию:

```text
local backups: 30 days
local base snapshots: 90 days
remote bundles: 30 days после checkpoint
tombstones: 90 days
media backups: 14 days
max versions per file: 50
```

Для MVP cleanup ручной:

```text
Settings → Cleanup old local data
```

Remote cleanup после MVP.

---

# 23. Checkpoints

Checkpoints не обязательны в первом MVP, но должны быть заложены в формат remote.

Назначение checkpoint:

```text
ускорить подключение нового устройства
не применять тысячи старых bundles
зафиксировать актуальное состояние vault
```

Checkpoint format:

```json
{
  "format_version": 2,
  "vault_id": "main-vault-id",
  "checkpoint_id": "checkpoint_000100",
  "created_at": "2026-06-28T02:00:00Z",
  "created_by_device_id": "desktop",
  "files": {
    "Inbox.md": {
      "hash": "hash",
      "size": 2048,
      "modified_at": 1782552000
    }
  }
}
```

---

# 24. UI/UX

## 24.1. Основные экраны

```text
Welcome / Setup
Select Vault
Select Remote
Yandex Auth
Dashboard
Local Changes
Remote Changes
Push Preview
Pull Preview
Conflict Resolver
Backups
Settings
Logs
Diagnostics
```

---

## 24.2. Dashboard

Показывать:

```text
Vault name
Vault path
Remote backend
Remote root
Device name
Last scan time
Last push time
Last fetch time
Last pull time
Local changes count
Remote changes count
Conflicts count
Yandex total space
Yandex used space
Yandex free space
```

Кнопки:

```text
Scan Local
Fetch Remote
Push Selected
Pull Selected
Resolve Conflicts
Settings
Logs
```

---

## 24.3. Local Changes screen

Таблица:

```text
checkbox
status
path
size
modified time
file kind
risk
```

Статусы:

```text
created
modified
deleted
media
ignored
conflict-risk
```

Действия:

```text
Push selected
Ignore selected
Preview
Open file
```

---

## 24.4. Remote Changes screen

Таблица:

```text
checkbox
source device
bundle id
created at
operation
path
size
risk
```

Действия:

```text
Pull selected
Preview manifest
Download bundle
Apply safe changes
Open conflicts
```

---

## 24.5. Conflict Resolver screen

Для text/Markdown:

```text
Top:
  path
  conflict type
  source device
  base hash
  local hash
  remote hash

Panels:
  Local
  Remote
  Result

Actions:
  Keep Local
  Keep Remote
  Save Both
  Append Remote to End
  Append Remote to Beginning
  Manual Merge
```

---

# 25. Android-specific requirements

## 25.1. Vault access

Android-приложение должно использовать Storage Access Framework.

Первый запуск:

```text
ACTION_OPEN_DOCUMENT_TREE
takePersistableUriPermission
read/write validation
```

---

## 25.2. Ограничение MVP

Поддерживается:

```text
Obsidian vault in Device storage
```

Не поддерживается:

```text
Obsidian app storage
```

---

## 25.3. Android workflow

MVP должен быть ручным:

```text
Open app
Scan
Fetch
Push/Pull
Apply
```

Не делать в MVP:

```text
background sync
automatic apply
silent conflict resolution
```

---

# 26. Desktop-specific requirements

Desktop-приложение должно уметь:

```text
выбрать локальную папку vault
сканировать обычную файловую систему
открыть файл в системном редакторе
открыть папку backup
экспортировать logs
```

После MVP:

```text
tray mode
file watcher
auto-scan
notifications
```

---

# 27. RemoteStorage abstraction

## 27.1. Интерфейс

```kotlin
interface RemoteStorage {
    suspend fun checkConnection(): RemoteCheckResult
    suspend fun getQuota(): RemoteQuota?

    suspend fun ensureDirectory(path: RemotePath)
    suspend fun exists(path: RemotePath): Boolean

    suspend fun list(path: RemotePath): List<RemoteObject>
    suspend fun uploadBytes(
        path: RemotePath,
        bytes: ByteArray,
        overwrite: Boolean
    ): RemoteUploadResult

    suspend fun downloadBytes(path: RemotePath): ByteArray

    suspend fun delete(path: RemotePath, permanently: Boolean = false)

    suspend fun move(
        from: RemotePath,
        to: RemotePath,
        overwrite: Boolean
    )

    suspend fun listReadyMarkers(vaultId: VaultId): List<ReadyMarker>
    suspend fun uploadManifest(manifest: SyncManifest)
    suspend fun downloadManifest(ref: ManifestRef): SyncManifest
    suspend fun uploadBundle(bundle: EncryptedBundle)
    suspend fun downloadBundle(ref: BundleRef): ByteArray
}
```

---

## 27.2. MVP implementation

```text
YandexDiskRestRemoteStorage
```

---

## 27.3. Test implementation

```text
LocalFolderRemoteStorage
```

Нужно для unit/integration tests без Yandex.

---

# 28. Yandex REST backend implementation notes

## 28.1. Connection check

Должен получать информацию о диске:

```text
total_space
used_space
trash_size
system folders if available
```

UI должен показывать:

```text
free_space = total_space - used_space
```

---

## 28.2. Upload

Логика:

```text
1. Запросить upload URL для remote path.
2. PUT bytes на upload URL.
3. Проверить результат.
4. Получить resource metadata.
5. Сравнить size.
```

---

## 28.3. Download

Логика:

```text
1. Запросить download URL для remote path.
2. GET bytes по download URL.
3. Проверить hash после скачивания.
```

---

## 28.4. List directory

Логика:

```text
GET resource metadata for directory
parse embedded items
map to RemoteObject
```

---

## 28.5. Errors

Обязательные ошибки:

```text
Unauthorized
Forbidden
NotFound
AlreadyExists
QuotaExceeded
NetworkError
RateLimited
ServerError
HashMismatch
UnexpectedResponse
```

---

## 28.6. Retry policy

Для сетевых ошибок:

```text
retry 3 times
exponential backoff
do not retry unsafe operation blindly if result unknown
```

Для upload:

```text
если неизвестно, завершился ли upload
→ проверить exists(path)
→ проверить size/hash where possible
→ только потом retry или fail
```

---

# 29. Logging

Приложение должно логировать:

```text
scan started
scan completed
local changes found
remote fetch started
remote manifests found
bundle build started
bundle built
upload started
upload completed
download started
download completed
apply started
file backed up
file applied
conflict detected
conflict resolved
hash mismatch
Yandex API error
```

Логи должны быть видны в UI.

Должна быть кнопка:

```text
Export diagnostic report
```

В diagnostic report нельзя включать:

```text
OAuth token
encryption key
passphrase
полное содержимое заметок
```

---

# 30. Security requirements

## 30.1. Remote token

OAuth token нельзя хранить plain text.

Android:

```text
Android Keystore / EncryptedSharedPreferences
```

Desktop MVP:

```text
encrypted local config
```

После MVP:

```text
OS credential storage
```

---

## 30.2. Bundle encryption

Для реального использования encrypted bundles обязательны.

MVP может иметь developer mode:

```text
encryption disabled
```

Но UI должен показывать предупреждение:

```text
Remote bundle is not encrypted.
Do not use this mode for real notes.
```

---

## 30.3. Manifest privacy

MVP:

```text
manifest может быть открытым
```

Но manifest раскрывает:

```text
paths
file names
file sizes
device names
timestamps
```

После MVP добавить:

```text
encrypted manifest mode
```

---

# 31. Acceptance criteria для MVP

## Scenario 1: Desktop → Yandex → Android

```text
Desktop изменяет Inbox.md.
Приложение видит изменение.
Пользователь делает Push selected.
Bundle и manifest появляются на Yandex Disk.
Android делает Fetch Remote.
Android видит Inbox.md как remote change.
Android делает Pull.
Inbox.md обновляется.
SHA-256 совпадает.
```

---

## Scenario 2: Android → Yandex → Desktop

```text
Android изменяет MobileInbox.md.
Android делает Push selected.
Desktop делает Fetch Remote.
Desktop видит remote change.
Desktop делает Pull.
Файл обновляется.
SHA-256 совпадает.
```

---

## Scenario 3: 10 Markdown files round-trip

```text
Создать 10 .md файлов.
Загрузить в Yandex Disk REST backend.
Скачать обратно.
Проверить SHA-256 каждого файла.
Все hashes совпадают.
```

Этот сценарий уже подтвержден Python-тестом и должен быть перенесен в automated integration test.

---

## Scenario 4: Conflict modify-vs-modify

```text
Desktop и Android изменяют один и тот же файл от одной base version.
Один device делает Push.
Второй device делает Pull.
Приложение обнаруживает local_hash != base_hash.
Показывает conflict resolver.
Пользователь выбирает Save Both.
Обе версии сохраняются.
Данные не теряются.
```

---

## Scenario 5: Delete-vs-modify

```text
Desktop удалил файл.
Android изменил этот же файл.
Android получает remote delete.
Приложение не удаляет файл автоматически.
Показывает delete-vs-modify conflict.
```

---

## Scenario 6: Backup before apply

```text
Перед применением remote bundle локальный файл копируется в backup.
После ошибки можно восстановить старую версию.
```

---

## Scenario 7: No double apply

```text
Один и тот же bundle не применяется дважды.
Повторный Pull показывает, что bundle already applied.
```

---

## Scenario 8: Quota warning

```text
Если Yandex Disk free space меньше ожидаемого размера upload,
приложение показывает предупреждение и не начинает upload без подтверждения.
```

---

# 32. Automated tests

## 32.1. Unit tests

```text
path normalization
ignore rules
hash calculation
snapshot diff
manifest serialization
bundle creation
bundle verification
conflict detection
tombstone handling
```

---

## 32.2. Integration tests: LocalFolderRemoteStorage

```text
desktop vault A
desktop vault B
local remote folder
push from A
pull to B
verify hashes
conflict test
delete-vs-modify test
```

---

## 32.3. Integration tests: Yandex Disk REST

На основе уже написанного Python-теста сделать KMP/CLI тест:

```text
create remote test folder
upload 1..10 .md files
list remote folder
download files
verify SHA-256
cleanup optional
```

Дополнительно:

```text
upload manifest
upload bundle
ready marker
fetch manifests
download bundle
verify bundle hash
```

---

# 33. План разработки

## Этап 0: Python validation — выполнено частично

Уже выполнено:

```text
Yandex REST API round-trip test
10 .md files
upload/list/download/hash verification
RESULT OK
```

Нужно добавить:

```text
test manifest upload
test bundle upload
test ready marker
test delete cleanup
test app:/ path if используется app-folder scope
```

---

## Этап 1: KMP project skeleton

```text
Создать KMP проект.
Подключить Compose Multiplatform.
Создать modules:
  shared-core
  shared-ui
  app-desktop
  app-android
  remote-api
  remote-yandex-rest
  storage-local
  crypto
```

---

## Этап 2: Core без Yandex

Remote:

```text
LocalFolderRemoteStorage
```

Сделать:

```text
scan vault
local state
snapshot diff
manifest
zip bundle
apply bundle
backup before apply
conflict detection
```

---

## Этап 3: YandexDiskRestRemoteStorage

Сделать:

```text
auth token settings
checkConnection
getQuota
ensureDirectory
upload bytes
download bytes
list directory
move/delete
ready marker
```

---

## Этап 4: Desktop MVP UI

Сделать:

```text
select vault
setup Yandex token
dashboard
scan local
local changes
push selected
fetch remote
remote changes
pull selected
logs
```

---

## Этап 5: Android MVP UI

Сделать:

```text
SAF vault picker
persisted permissions
setup Yandex token
dashboard
scan local
push selected
fetch remote
pull selected
logs
```

---

## Этап 6: Conflict resolver

Сделать:

```text
conflict list
conflict details
keep local
keep remote
save both
append remote to end
append remote to beginning
manual merge editor
```

---

## Этап 7: Encryption

Сделать:

```text
bundle encryption
secure token storage
passphrase setup
wrong passphrase handling
encryption status in UI
```

---

## Этап 8: Hardening

Сделать:

```text
retry policy
partial upload recovery
tmp cleanup
diagnostic export
backup restore
quota warnings
retention cleanup
```

---

# 34. Что не входит в MVP

```text
background sync
automatic sync daemon
GitHub backend
S3 backend
WebDAV backend
full version history UI
Markdown-aware merge
CRDT
iOS support
large media sync by default
binary delta
collaborative editing
Obsidian plugin
```

---

# 35. Главные риски

## Риск 1: Потеря данных при apply

Решение:

```text
backup before apply
hash check
base_hash conflict detection
no silent overwrite
```

---

## Риск 2: Yandex quota почти заполнена

Решение:

```text
getQuota on dashboard
pre-upload size check
warning before upload
retention cleanup
manual cleanup tools
```

---

## Риск 3: Android SAF медленный

Решение:

```text
ручной режим
кэш metadata
сканировать только нужные типы файлов
не включать media автоматически
```

---

## Риск 4: Manifest раскрывает имена файлов

Решение MVP:

```text
предупреждение пользователю
```

Решение после MVP:

```text
encrypted manifest mode
```

---

## Риск 5: Remote содержит недогруженный bundle

Решение:

```text
tmp upload path
ready marker
pull ignores files without ready marker
```

---

# 36. Итоговая формула MVP

MVP должен реализовать:

```text
KMP Compose Desktop + Android приложение
+ Obsidian vault folder access
+ Yandex Disk REST API backend
+ manual scan
+ selected file push
+ remote fetch
+ selected file pull
+ manifest v2
+ zip bundle
+ SHA-256 verification
+ ready markers
+ backup before apply
+ conflict detection
+ basic conflict resolver
```

Критерий успеха:

```text
Пользователь может вручную и безопасно синхронизировать Markdown-файлы Obsidian между Windows и Android через Yandex Disk REST API, видя все изменения и не рискуя молчаливой потерей данных.
```
