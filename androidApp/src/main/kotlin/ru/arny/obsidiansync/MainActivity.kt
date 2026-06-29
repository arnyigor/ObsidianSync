package ru.arny.obsidiansync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val demoMode = intent.getBooleanExtra(EXTRA_DEMO_MODE, false)

        setContent {
            val preferences = remember { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
            var vaultUri by remember {
                mutableStateOf(
                    if (demoMode) null else preferences.getString(KEY_VAULT_URI, null)?.let(Uri::parse),
                )
            }
            val vaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    preferences.edit().putString(KEY_VAULT_URI, it.toString()).apply()
                    vaultUri = it
                }
            }
            val syncGateway = remember(demoMode) {
                if (demoMode) DemoSyncGateway() else AndroidSyncGateway(applicationContext) { vaultUri }
            }
            val vaultName = if (demoMode) "Demo Vault" else vaultUri?.let { uri ->
                DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment
            }

            App(
                vaultPath = vaultName,
                onChooseVaultFolder = if (demoMode) null else ({ vaultPicker.launch(vaultUri) }),
                onOpenYandexLogin = if (demoMode) null else ({
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://oauth.yandex.ru/")))
                }),
                syncGateway = syncGateway,
            )
        }
    }

    private companion object {
        const val PREFERENCES = "obsidelta_settings"
        const val KEY_VAULT_URI = "vault_uri"
        const val EXTRA_DEMO_MODE = "demo"
    }
}

@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun AppAndroidPreview() {
    App(vaultPath = "My Obsidian Vault")
}
