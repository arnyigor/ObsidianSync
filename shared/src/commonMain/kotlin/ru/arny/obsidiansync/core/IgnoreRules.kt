package ru.arny.obsidiansync.core

object DefaultIgnoreRules {
    val patterns: List<String> = listOf(
        ".obsidelta/",
        ".git/",
        ".trash/",
        ".DS_Store",
        "Thumbs.db",
        "desktop.ini",
        "*.tmp",
        "*.temp",
        "*.bak",
        "*.swp",
        "*.part",
        "*.crdownload",
        ".obsidian/workspace.json",
        ".obsidian/workspace-mobile.json",
        ".obsidian/workspace-desktop.json",
        ".embeddings/",
        ".llm-cache/",
        ".rag-cache/",
    )
}

class ObsideltaIgnore(
    patterns: List<String> = DefaultIgnoreRules.patterns,
) {
    private val rules = patterns
        .map { it.trim().replace('\\', '/') }
        .filter { it.isNotEmpty() && !it.startsWith('#') }

    fun isIgnored(path: String): Boolean {
        val normalized = normalizeVaultPath(path)
        return rules.any { rule -> matches(rule, normalized) }
    }

    private fun matches(rule: String, path: String): Boolean {
        if (rule.endsWith('/')) {
            val directory = rule.trimEnd('/')
            return path == directory || path.startsWith("$directory/")
        }

        if ('*' !in rule) {
            return path == rule || path.endsWith("/$rule")
        }

        val regex = rule
            .split('*')
            .joinToString(".*") { Regex.escape(it) }
            .let { Regex("^$it$") }
        return regex.matches(path) || regex.matches(path.substringAfterLast('/'))
    }
}

fun normalizeVaultPath(path: String): String = path
    .replace('\\', '/')
    .split('/')
    .filter { it.isNotBlank() && it != "." }
    .fold(emptyList<String>()) { acc, part ->
        when (part) {
            ".." -> acc.dropLast(1)
            else -> acc + part
        }
    }
    .joinToString("/")

fun classifyVaultFile(path: String): FileKind {
    val normalized = normalizeVaultPath(path).lowercase()
    if (normalized.startsWith(".obsidian/")) return FileKind.Settings
    return when (normalized.substringAfterLast('.', missingDelimiterValue = "")) {
        "md", "txt", "json", "canvas" -> FileKind.Text
        "png", "jpg", "jpeg", "webp", "gif", "pdf" -> FileKind.Media
        "mp4", "zip", "7z", "rar", "exe", "bin", "gguf" -> FileKind.Binary
        else -> FileKind.Unknown
    }
}

fun isAutoIncludedFile(path: String): Boolean = classifyVaultFile(path) == FileKind.Text
fun requiresManualSelection(path: String): Boolean = classifyVaultFile(path) == FileKind.Media
fun isBlockedByDefault(path: String): Boolean = classifyVaultFile(path) == FileKind.Binary
