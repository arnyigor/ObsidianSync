package ru.arny.obsidiansync.core

class RemoteLayout(private val root: RemotePath) {
    fun vaultRoot(vaultId: VaultId): RemotePath = root.child("vaults/${vaultId.value}")
    fun meta(vaultId: VaultId): RemotePath = vaultRoot(vaultId).child("meta")
    fun devices(vaultId: VaultId): RemotePath = vaultRoot(vaultId).child("devices")
    fun ready(vaultId: VaultId): RemotePath = vaultRoot(vaultId).child("ready")
    fun tmpUploads(vaultId: VaultId): RemotePath = vaultRoot(vaultId).child("tmp/uploads")

    fun manifestPath(vaultId: VaultId, bundleId: String, createdAt: String): RemotePath =
        vaultRoot(vaultId).child("manifests/${yearMonth(createdAt)}/$bundleId.manifest.json")

    fun bundlePath(vaultId: VaultId, bundleId: String, createdAt: String): RemotePath =
        vaultRoot(vaultId).child("bundles/${yearMonth(createdAt)}/$bundleId.bundle.enc")

    fun readyMarkerPath(vaultId: VaultId, bundleId: String): RemotePath =
        ready(vaultId).child("$bundleId.ready")

    fun tmpManifestPath(vaultId: VaultId, bundleId: String): RemotePath =
        tmpUploads(vaultId).child("$bundleId.manifest.json.tmp")

    fun tmpBundlePath(vaultId: VaultId, bundleId: String): RemotePath =
        tmpUploads(vaultId).child("$bundleId.bundle.enc.tmp")

    private fun yearMonth(createdAt: String): String {
        val year = createdAt.take(4).takeIf { it.length == 4 } ?: "unknown"
        val month = createdAt.drop(5).take(2).takeIf { it.length == 2 } ?: "unknown"
        return "$year/$month"
    }
}

fun buildBundleId(deviceId: String, sequence: Int, createdAt: String): String {
    val safeDevice = deviceId.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .trim('_')
        .ifBlank { "device" }
    val timestamp = createdAt
        .replace("-", "")
        .replace(":", "")
        .replace(".", "")
        .replace(Regex("[^0-9TZ]"), "")
    return "${safeDevice}_${sequence.toString().padStart(6, '0')}_$timestamp"
}
