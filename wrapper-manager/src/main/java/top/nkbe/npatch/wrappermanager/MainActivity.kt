package top.nkbe.npatch.wrappermanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFF98D5B7), secondary = Color(0xFFFFB3A8), tertiary = Color(0xFFB4C5F4),
                background = Color(0xFF141917), surface = Color(0xFF141917),
            ) else lightColorScheme(
                primary = Color(0xFF25634A), secondary = Color(0xFFAF4D45), tertiary = Color(0xFF375DAC),
                background = Color(0xFFF8FAF9), surface = Color(0xFFF8FAF9),
            )
            val typography = Typography().run { copy(
                titleLarge = titleLarge.copy(letterSpacing = 0.sp), titleMedium = titleMedium.copy(letterSpacing = 0.sp),
                titleSmall = titleSmall.copy(letterSpacing = 0.sp), bodyLarge = bodyLarge.copy(letterSpacing = 0.sp),
                bodyMedium = bodyMedium.copy(letterSpacing = 0.sp), bodySmall = bodySmall.copy(letterSpacing = 0.sp),
                labelLarge = labelLarge.copy(letterSpacing = 0.sp),
            ) }
            MaterialTheme(colorScheme = colors, typography = typography, shapes = Shapes(
                small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp),
            )) {
                WrapperScreen(initialUri = intent.data.takeIf { intent.action == Intent.ACTION_VIEW })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WrapperScreen(initialUri: Uri?, model: WrapperViewModel = viewModel()) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val installPermission = stringResource(R.string.install_permission)
    val notInstalled = stringResource(R.string.not_installed)
    var showApps by rememberSaveable { mutableStateOf(false) }
    var consumedUri by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ApkPickerContract()) { uri ->
        if (uri != null) model.selectUri(uri)
    }
    val gadgetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.selectGadget(uri)
    }
    val scriptPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.selectGadgetScript(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null) model.export(uri)
    }
    LaunchedEffect(initialUri) {
        if (!consumedUri && initialUri != null) { consumedUri = true; model.selectUri(initialUri) }
    }
    fun perform(action: () -> Unit) { try { action() } catch (e: Exception) { model.error(e) } }
    fun resultUri(): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", state.output!!)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(Unit) }, enabled = !state.busy) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.select_apk))
                }
                OutlinedButton(onClick = { showApps = true; model.loadApps() }, enabled = !state.busy) {
                    Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.select_app))
                }
            }
            state.selected?.let { selected ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(selected.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected.label, style = MaterialTheme.typography.titleMedium)
                        Text(selected.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(value = state.packageName, onValueChange = model::packageName,
                    label = { Text(stringResource(R.string.package_name)) }, enabled = !state.busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = state.filename, onValueChange = model::filename,
                    label = { Text(stringResource(R.string.filename)) }, enabled = !state.busy,
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.signature_compat), modifier = Modifier.weight(1f))
                    Switch(checked = state.signatureCompat, onCheckedChange = model::signatureCompat, enabled = !state.busy)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.enable_gadget), modifier = Modifier.weight(1f))
                    Switch(checked = state.gadgetEnabled, onCheckedChange = model::gadgetEnabled, enabled = !state.busy)
                }
                if (state.gadgetEnabled) {
                    OutlinedButton(onClick = { gadgetPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.select_gadget))
                    }
                    Text(state.gadget?.let {
                        stringResource(R.string.selected_gadget, it.displayName, it.detail ?: "")
                    } ?: stringResource(R.string.no_gadget_selected),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.gadget_mode), style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        GadgetMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(selected = state.gadgetMode == mode, onClick = { model.gadgetMode(mode) },
                                enabled = !state.busy, shape = SegmentedButtonDefaults.itemShape(index, GadgetMode.entries.size)) {
                                Text(stringResource(if (mode == GadgetMode.LISTEN) R.string.gadget_listen else R.string.gadget_script))
                            }
                        }
                    }
                    if (state.gadgetMode == GadgetMode.LISTEN) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(value = state.gadgetAddress, onValueChange = model::gadgetAddress,
                                label = { Text(stringResource(R.string.gadget_address)) }, enabled = !state.busy,
                                singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = state.gadgetPort, onValueChange = model::gadgetPort,
                                label = { Text(stringResource(R.string.gadget_port)) }, enabled = !state.busy,
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(0.55f))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.gadget_wait), modifier = Modifier.weight(1f))
                            Switch(checked = state.gadgetWaitForClient, onCheckedChange = model::gadgetWaitForClient,
                                enabled = !state.busy)
                        }
                    } else {
                        OutlinedButton(onClick = { scriptPicker.launch(arrayOf("text/*", "application/javascript", "*/*")) },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.select_gadget_script))
                        }
                        Text(state.gadgetScript?.displayName ?: stringResource(R.string.no_gadget_script_selected),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(onClick = model::generate, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.generate))
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.output?.let {
                HorizontalDivider()
                Text(stringResource(R.string.generated), style = MaterialTheme.typography.titleMedium)
                Text(it.name, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { perform {
                        try {
                            exporter.launch(state.filename)
                        } catch (_: ActivityNotFoundException) {
                            model.exportToDownloads()
                        }
                    } }, enabled = !state.busy) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.export))
                    }
                    OutlinedButton(onClick = { perform {
                        model.checkInstall()
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                            model.notice(installPermission)
                        } else {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(resultUri(), "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } }, enabled = !state.busy) {
                        Icon(Icons.Outlined.GetApp, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.install))
                    }
                    OutlinedButton(onClick = { perform {
                        model.checkInstall()
                        model.checkOpen()
                        val launch = context.packageManager.getLaunchIntentForPackage(state.packageName)
                            ?: error(notInstalled)
                        context.startActivity(launch)
                    } }, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.open))
                    }
                    OutlinedButton(onClick = { perform {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                            .setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_STREAM, resultUri())
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    } }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(stringResource(R.string.share))
                    }
                }
            }
            if (state.logs.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.build_log), style = MaterialTheme.typography.titleSmall)
                Text(state.logs.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showApps) {
        var search by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showApps = false }, title = { Text(stringResource(R.string.select_app)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(search, { search = it }, singleLine = true,
                        label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                    if (state.appsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val visible = state.apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
                    if (!state.appsLoading && visible.isEmpty()) Text(stringResource(R.string.no_apps))
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(visible, key = { it.packageName }) { item ->
                            Column(Modifier.fillMaxWidth().clickable { showApps = false; model.selectInstalled(item.packageName) }
                                .padding(vertical = 12.dp)) {
                                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                                Text(item.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showApps = false }) { Text(stringResource(R.string.close)) } })
    }
}
