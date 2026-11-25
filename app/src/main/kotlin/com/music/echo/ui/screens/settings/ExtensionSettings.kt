package com.music.echo.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.music.echo.extensions.AddonEndpoint
import com.music.echo.extensions.ExtensionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import echo.music.iad1tya.BuildConfig
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.PreferExtensionAudioKey
import echo.music.iad1tya.ui.component.Material3SettingsGroup
import echo.music.iad1tya.ui.component.Material3SettingsItem
import echo.music.iad1tya.ui.utils.backToMain
import echo.music.iad1tya.utils.rememberPreference
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch




@HiltViewModel
class ExtensionSettingsViewModel @Inject constructor(
    private val extensionManager: ExtensionManager
) : ViewModel() {
    val addons = extensionManager.observeAddons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addAddon(name: String, url: String) {
        viewModelScope.launch {
            extensionManager.addAddon(AddonEndpoint(UUID.randomUUID().toString(), name, url))
        }
    }

    fun toggleAddon(id: String, enabled: Boolean) {
        viewModelScope.launch {
            extensionManager.toggleAddon(id, enabled)
        }
    }

    fun removeAddon(id: String) {
        viewModelScope.launch {
            extensionManager.removeAddon(id)
        }
    }
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionSettingsScreen(
    navController: NavController? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    viewModel: ExtensionSettingsViewModel = hiltViewModel()
) {
    val addons by viewModel.addons.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var showLosslessSetup by remember { mutableStateOf(false) }
    var browserUnavailable by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val openDeployment: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            browserUnavailable = true
        }
    }
    val (preferExtensionAudio, onPreferExtensionAudioChange) =
        rememberPreference(key = PreferExtensionAudioKey, defaultValue = true)
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .windowInsetsPadding(echo.music.iad1tya.LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.windowInsetsPadding(echo.music.iad1tya.LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)))
        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = "Playback Priority",
            items = listOf(
                Material3SettingsItem(
                    title = { Text("Prefer Extension Audio") },
                    description = { Text("Search extensions before YouTube Music") },
                    icon = painterResource(R.drawable.extension),
                    trailingContent = {
                        Switch(
                            checked = preferExtensionAudio,
                            onCheckedChange = onPreferExtensionAudioChange,
                            modifier = Modifier.scale(0.8f)
                        )
                    },
                    onClick = { onPreferExtensionAudioChange(!preferExtensionAudio) }
                )
            )
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.extension_setup_title),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.extension_setup_320_title)) },
                    description = { Text(stringResource(R.string.extension_setup_320_description)) },
                    icon = painterResource(R.drawable.ic_hq),
                    trailingContent = { ExtensionSetupArrow() },
                    onClick = { openDeployment(CLOUDFLARE_DEPLOY_URL) }
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.extension_setup_lossless_title)) },
                    description = { Text(stringResource(R.string.extension_setup_lossless_description)) },
                    icon = painterResource(R.drawable.extension),
                    trailingContent = { ExtensionSetupArrow() },
                    onClick = { showLosslessSetup = true }
                )
            )
        )

        Spacer(Modifier.height(16.dp))

        if (addons.isNotEmpty()) {
            Material3SettingsGroup(
                title = "Installed Addons",
                items = addons.map { addon ->
                    Material3SettingsItem(
                        title = { Text(addon.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        description = { Text(addon.url, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        customIcon = {
                            coil3.compose.SubcomposeAsyncImage(
                                model = "${addon.url.removeSuffix("/")}/favicon.ico",
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                loading = {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(echo.music.iad1tya.R.drawable.extension),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                error = {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(echo.music.iad1tya.R.drawable.extension),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Switch(
                                    checked = addon.enabled,
                                    onCheckedChange = { viewModel.toggleAddon(addon.id, it) },
                                    modifier = Modifier.scale(0.8f)
                                )
                                androidx.compose.material3.IconButton(
                                    onClick = { viewModel.removeAddon(addon.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.delete),
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        },
                        onClick = {}
                    )
                }
            )

            Spacer(Modifier.height(16.dp))
        }

        Material3SettingsGroup(
            title = "Add new",
            items = listOf(
                Material3SettingsItem(
                    title = { Text("Add Custom Server") },
                    description = { Text("Configure a custom addon host") },
                    icon = painterResource(R.drawable.add),
                    onClick = { showDialog = true }
                )
            )
        )

        Spacer(Modifier.height(32.dp))

        if (showDialog) {
            var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
            var url by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }

            echo.music.iad1tya.ui.component.TextFieldDialog(
                title = { Text("Add Extension") },
                textFields = listOf("Name" to name, "Server URL" to url),
                onTextFieldsChange = { index, value ->
                    if (index == 0) name = value
                    if (index == 1) url = value
                },
                onDismiss = { showDialog = false },
                onDoneMultiple = { inputs ->
                    val finalName = inputs.getOrNull(0)?.trim() ?: ""
                    val finalUrl = inputs.getOrNull(1)?.trim() ?: ""
                    if (finalUrl.isNotBlank()) {
                        viewModel.addAddon(finalName, finalUrl)
                        showDialog = false
                    }
                }
            )
        }
    }
    TopAppBar(
        title = { Text("Extension Sources") },
        navigationIcon = {
            if (navController != null) {
                echo.music.iad1tya.ui.component.IconButton(
                    onClick = { navController.navigateUp() },
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        painterResource(echo.music.iad1tya.R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )

    if (showLosslessSetup) {
        LosslessSetupDialog(
            onDismiss = { showLosslessSetup = false },
            onDeploy = {
                showLosslessSetup = false
                openDeployment(vercelDeployUrl())
            }
        )
    }

    if (browserUnavailable) {
        AlertDialog(
            onDismissRequest = { browserUnavailable = false },
            text = { Text(stringResource(R.string.extension_setup_browser_unavailable)) },
            confirmButton = {
                TextButton(onClick = { browserUnavailable = false }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}

@Composable
private fun ExtensionSetupArrow() {
    Icon(
        painter = painterResource(R.drawable.navigate_next),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LosslessSetupDialog(onDismiss: () -> Unit, onDeploy: () -> Unit) {
    val context = LocalContext.current
    val keyLabel = stringResource(R.string.extension_setup_key_label)
    var keyCopied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extension_setup_lossless_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.extension_setup_lossless_key_instructions))
                OutlinedButton(onClick = {
                    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val key = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    val clip = ClipData.newPlainText(keyLabel, key)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(clip)
                    keyCopied = true
                }) {
                    Text(stringResource(if (keyCopied) R.string.extension_setup_key_copied else R.string.extension_setup_key_copy))
                }
                val connectInstructions = if (BuildConfig.HR_MUSIC_VERCEL_CALLBACK_HOST.isEmpty()) {
                    R.string.extension_setup_connect_by_sharing
                } else {
                    R.string.extension_setup_lossless_connect_instructions
                }
                Text(stringResource(connectInstructions))
            }
        },
        confirmButton = {
            TextButton(onClick = onDeploy, enabled = keyCopied) {
                Text(stringResource(R.string.extension_setup_open_vercel))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private const val CLOUDFLARE_DEPLOY_URL =
    "https://deploy.workers.cloudflare.com/?url=https%3A%2F%2Fgithub.com%2FEchoMusicApp%2F320kbs-extension"

private fun vercelDeployUrl(): String = Uri.parse("https://vercel.com/new/clone").buildUpon().apply {
    appendQueryParameter("repository-url", "https://github.com/EchoMusicApp/lossless-extension")
    appendQueryParameter("project-name", "hr-music-lossless")
    appendQueryParameter("env", "QOBUZ_ID_SECRET")
    appendQueryParameter("envDescription", "Paste the private setup key generated by HR Music.")
    BuildConfig.HR_MUSIC_VERCEL_CALLBACK_HOST.takeIf { it.isNotEmpty() }?.let { host ->
        appendQueryParameter("redirect-url", "https://$host/extensions/vercel")
    }
}.toString()
