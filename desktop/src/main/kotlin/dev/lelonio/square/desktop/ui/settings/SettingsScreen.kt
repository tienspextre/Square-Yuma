package dev.lelonio.square.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Play
import dev.lelonio.square.desktop.AppState
import dev.lelonio.square.desktop.AppVersion
import dev.lelonio.square.desktop.DiagnosticsReport
import dev.lelonio.square.desktop.DropTargetHandler
import dev.lelonio.square.desktop.GlassProfile
import dev.lelonio.square.desktop.Language
import dev.lelonio.square.desktop.Renderer
import dev.lelonio.square.desktop.Screen
import dev.lelonio.square.desktop.ShortcutDispatch
import dev.lelonio.square.desktop.ThemeMode
import dev.lelonio.square.desktop.str
import dev.lelonio.square.desktop.ui.components.BarClearance
import dev.lelonio.square.desktop.ui.components.Gutter
import dev.lelonio.square.desktop.ui.components.Pill
import dev.lelonio.square.desktop.ui.components.SectionLabel
import dev.lelonio.square.desktop.ui.components.Segmented
import dev.lelonio.square.desktop.ui.components.Title
import dev.lelonio.square.desktop.ui.components.Toggle
import dev.lelonio.square.ui.theme.Ink
import dev.lelonio.square.ui.theme.InkDim
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: AppState) {
    val prefs = state.prefs
    var url by remember { mutableStateOf(prefs.serverUrl.value.ifBlank { "http://" }) }
    var user by remember { mutableStateOf(prefs.username.value) }
    var pass by remember { mutableStateOf(prefs.password.value) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val field = Modifier.fillMaxWidth().onFocusChanged { state.typing = it.isFocused }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 680.dp).fillMaxSize(),
            contentPadding = PaddingValues(Gutter, 20.dp, Gutter, BarClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Title("settings", Modifier.padding(bottom = 10.dp)) }
            item { SectionLabel("subsonic_settings_title") }
            item { OutlinedTextField(url, { url = it }, field, label = { Text(str("subsonic_server_url")) }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(user, { user = it }, field, label = { Text(str("subsonic_username")) }, singleLine = true, textStyle = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedTextField(
                    pass,
                    { pass = it },
                    field,
                    label = { Text(str("subsonic_password")) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(PhosphorIcons.Fill.Play, "connect", accent = true) {
                        prefs.save(url, user, pass)
                        status = str("subsonic_connecting")
                        scope.launch {
                            state.client.ping().fold(
                                { status = "${str("connected")} · ${it.type} ${it.serverVersion}"; state.generation++ },
                                { status = str("subsonic_error", it.message.orEmpty()) },
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    status?.let { Text(it, color = InkDim, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item {
                TextButton(onClick = {
                    scope.launch {
                        status = if (state.client.startScan().getOrDefault(false)) str("desktop_rescan_started") else str("desktop_rescan_failed")
                    }
                }) { Text(str("subsonic_rescan_library")) }
            }

            item { SectionLabel("downloads", Modifier.padding(top = 14.dp)) }
            item {
                Column {
                    Text(
                        "${state.downloads.kept.size} ${str("desktop_songs_unit")} · %.2f GB · ${state.downloads.root.path}".format(state.downloads.bytes() / 1e9),
                        color = InkDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Toggle("offline_mode", state.offlineMode) { state.useOfflineMode(it) }
                    Spacer(Modifier.height(10.dp))
                    Text(str("desktop_cache_limit"), color = InkDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                    val limits = listOf(0, 1, 2, 5, 10, 20)
                    Segmented(limits.map { if (it == 0) "desktop_unlimited" else "$it GB" }, limits.indexOf(state.cacheLimitGb).coerceAtLeast(0)) {
                        state.setCacheLimit(limits[it])
                    }
                    Row {
                        TextButton({ runCatching { state.downloads.root.mkdirs(); Desktop.getDesktop().open(state.downloads.root) } }) {
                            Text(str("desktop_open_folder"))
                        }
                        if (state.downloads.kept.isNotEmpty()) {
                            TextButton({ state.downloads.removeAll(); state.say(str("desktop_removed_all_downloads")) }) {
                                Text(str("remove_all_downloads"))
                            }
                        }
                    }
                }
            }

            item { SectionLabel("about", Modifier.padding(top = 14.dp)) }
            item {
                var includePaths by remember { mutableStateOf(false) }
                var savingReport by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val u = state.update
                    Text(
                        buildString {
                            append(str("version")).append(' ').append(AppVersion)
                            if (u != null) append(" · ").append(str("update_available", u.version))
                            else if (state.updateChecking) append(" · ").append(str("update_checking"))
                        },
                        color = InkDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Toggle("desktop_beta_channel", state.betaChannel) { state.useBetaChannel(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val found = state.findUpdate(force = true)
                                    if (found == null) {
                                        val err = state.updateError
                                        state.say(if (err != null) str("desktop_update_failed", err) else str("update_none"))
                                    }
                                }
                            },
                            enabled = !state.updateChecking && !state.updateDownloading,
                        ) {
                            Text(str(if (state.updateChecking) "update_checking" else "check_for_updates"))
                        }
                        if (u != null) {
                            TextButton(
                                onClick = { state.applyUpdate() },
                                enabled = !state.updateDownloading,
                            ) {
                                Text(
                                    when {
                                        state.updateDownloading -> state.updateProgress?.let { "${str("downloading")} ${(it * 100).toInt()}%" } ?: str("update_downloading")
                                        u.canInstallInApp -> "${str("update_install")} ${u.version}"
                                        else -> str("desktop_open_release_page")
                                    },
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { includePaths = !includePaths }
                            .padding(vertical = 2.dp, horizontal = 2.dp),
                    ) {
                        Checkbox(checked = includePaths, onCheckedChange = { includePaths = it })
                        Spacer(Modifier.width(4.dp))
                        Text(str("desktop_include_file_paths"), color = Ink, style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                if (savingReport) return@TextButton
                                savingReport = true
                                scope.launch {
                                    runCatching {
                                        DiagnosticsReport.buildZip(
                                            state,
                                            DiagnosticsReport.Options(includeFilePaths = includePaths),
                                        )
                                    }.onSuccess { zip ->
                                        state.lastReportPath = zip.absolutePath
                                        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(zip.absolutePath), null) }
                                        state.say(str("desktop_report_saved_to", zip.absolutePath))
                                    }.onFailure {
                                        state.say(str("report_failed"))
                                    }
                                    savingReport = false
                                }
                            },
                            enabled = !savingReport,
                        ) {
                            Text(str(if (savingReport) "report_preparing" else "save_report"))
                        }
                        state.lastReportPath?.let { path ->
                            TextButton({ DropTargetHandler.openInFolder(state, path) }) {
                                Text(str("desktop_show_in_folder"))
                            }
                        }
                    }
                    state.lastReportPath?.let { path ->
                        Text(
                            File(path).name,
                            color = InkDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SectionLabel("theme_title", Modifier.padding(top = 14.dp)) }
            item {
                Segmented(listOf("theme_dark", "theme_light", "desktop_theme_system"), state.themeMode.ordinal) {
                    state.setTheme(ThemeMode.entries[it])
                }
            }

            item { SectionLabel("font_title", Modifier.padding(top = 14.dp)) }
            item {
                Segmented(listOf("desktop_font_inter", "desktop_font_system"), if (state.systemFont) 1 else 0) {
                    state.useSystemFont(it == 1)
                }
            }

            item { SectionLabel("language", Modifier.padding(top = 14.dp)) }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Language.entries.forEach { lang ->
                        val selected = lang == state.language
                        Text(
                            lang.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) Ink else InkDim,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Ink.copy(alpha = 0.07f))
                                .clickable { state.chooseLanguage(lang) }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            item { SectionLabel("glass", Modifier.padding(top = 14.dp)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(
                        listOf("desktop_glass_performance", "glass_balanced", "glass_quality"),
                        state.glassProfile.ordinal,
                    ) { state.setGlass(GlassProfile.entries[it]) }
                    val noteKey = when (state.glassProfile) {
                        GlassProfile.Performance -> "glass_performance_note"
                        GlassProfile.Balanced -> "glass_balanced_note"
                        GlassProfile.Quality -> "glass_quality_note"
                    }
                    Text(
                        str(noteKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkDim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionLabel("desktop_renderer", Modifier.padding(top = 14.dp)) }
            item {
                Segmented(listOf("desktop_renderer_auto", "desktop_renderer_opengl", "desktop_renderer_software"), state.renderer.ordinal) {
                    state.chooseRenderer(Renderer.entries[it])
                }
            }

            item { Toggle("desktop_close_to_tray", state.closeToTray) { state.useCloseToTray(it) } }
            if (!state.traySupported) {
                item {
                    Text(
                        str("desktop_tray_unavailable"),
                        color = InkDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            audioSettingsItems(state)

            item { SectionLabel("desktop_shortcuts", Modifier.padding(top = 14.dp)) }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ink.copy(alpha = 0.05f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShortcutDispatch.ALL_SHORTCUTS.forEach { spec ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                str(spec.labelKey),
                                color = Ink,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                spec.keys,
                                color = InkDim,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Ink.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            item { TextButton(onClick = { state.root(Screen.Home) }) { Text(str("desktop_done")) } }
        }
    }
}
