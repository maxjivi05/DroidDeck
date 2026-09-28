package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.GameEnvironment.Origin
import com.droiddeck.launcher.core.GameEnvironmentOptions
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun GameEnvironmentRow(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(stringResource(R.string.game_env_title), stringResource(R.string.game_env_hint)) {
        ValueChip(stringResource(R.string.game_env_edit), open, modifier = modifier) { open = true }
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Its own window: the launcher's pane focus must not track the controls in here.
        CompositionLocalProvider(LocalFrontFocus provides null, LocalChipMinWidth provides 120.dp) {
            GameEnvironmentPage { open = false }
        }
    }
}

/** A value being typed: an empty name is a new custom variable. */
private data class EnvEdit(val name: String, val value: String)

@Composable
private fun GameEnvironmentPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val host = rememberMenuHost()
    val preset = remember { SessionPrefs.fexPreset(context) }
    var config by remember { mutableStateOf<GameEnvironment.Config?>(null) }
    var games by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var scope by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EnvEdit?>(null) }
    var askId by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val profileFocus = remember { FocusRequester() }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                GameEnvironmentStore.read(context) to Library.steamGames(context).map { it.appId.toString() to it.name }
            }
        }.onSuccess { (settings, installed) -> config = settings; games = installed }.onFailure { failed = true }
    }
    LaunchedEffect(config != null) {
        if (config != null && keyboard) {
            withFrameNanos { }
            runCatching { profileFocus.requestFocus() }
        }
    }
    fun save(next: GameEnvironment.Config) {
        config = next
        failed = false
        GameEnvironmentStore.saveLater(context, next) { stored -> config = stored; failed = true }
    }
    // Reset and deleting the last row remove the focused control; the pad keeps a place to be.
    fun refocus() { if (keyboard) runCatching { profileFocus.requestFocus() } }
    val current = config
    val profileName = if (scope.isEmpty()) stringResource(R.string.game_env_shared)
        else games.firstOrNull { it.first == scope }?.second ?: stringResource(R.string.game_env_profile, scope)
    Box(Modifier.fillMaxSize().background(pal.background).controllerBack(onClose), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 760.dp)) {
            SettingsPage(
                host, title = stringResource(R.string.game_env_title), onBack = onClose,
                lede = stringResource(R.string.game_env_lede), compactLayout = true,
                action = {
                    if (current != null) ProfileChip(host, current, games, scope, profileName, profileFocus,
                        onPick = { scope = it }, onOther = { askId = true })
                },
            ) {
                if (failed) Note(stringResource(R.string.game_env_error))
                if (current == null) {
                    if (!failed) Text(stringResource(R.string.game_env_loading), fontSize = 13.sp, color = colors.onSurfaceVariant)
                } else {
                    val entries = GameEnvironment.resolve(current, preset, scope)
                    val inherited = GameEnvironment.resolve(current.withEntries(scope, emptyMap()), preset, scope).associateBy { it.name }
                    fun update(name: String, value: String?) = save(current.withEntries(scope, current.entries(scope) + (name to value)))
                    SettingsGroup(stringResource(R.string.game_env_variables), compact = true) {
                        for (entry in entries) VariableRow(
                            host, entry, inherited[entry.name],
                            onSet = { update(entry.name, it) },
                            onRestore = {
                            save(current.withEntries(scope, current.entries(scope) - entry.name))
                            if (inherited[entry.name] == null) refocus()
                        },
                            onEdit = { editing = EnvEdit(entry.name, entry.value.orEmpty()) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Box {
                            val open = host.open == "add"
                            SecondaryButton(stringResource(R.string.game_env_add), compact = true) { host.open = if (open) null else "add" }
                            AnchoredMenu(open, onDismiss = { if (host.open == "add") host.open = null }, title = stringResource(R.string.game_env_add)) { first ->
                                MenuItem(stringResource(R.string.game_env_custom), checked = false, focusRequester = first) {
                                    host.open = null; editing = EnvEdit("", "")
                                }
                                val present = entries.mapTo(HashSet()) { it.name }
                                for (option in GameEnvironmentOptions.all.filter { it.name !in present }.sortedBy { it.name.uppercase(Locale.ROOT) }) {
                                    MenuItem(option.name, checked = false) {
                                        host.open = null
                                        if (option.value.isEmpty()) editing = EnvEdit(option.name, "") else update(option.name, option.value)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (current.entries(scope).isNotEmpty()) FocusText(stringResource(R.string.game_env_reset), colors.error) { confirmReset = true }
                    }
                    Text(
                        stringResource(R.string.game_env_footer), fontSize = 12.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
    editing?.let { edit ->
        ValueDialog(edit, onDismiss = { editing = null }) { name, value ->
            editing = null
            config?.let { save(it.withEntries(scope, it.entries(scope) + (name to value))) }
        }
    }
    if (askId) AppIdDialog(onDismiss = { askId = false }) { id -> askId = false; scope = id }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text(stringResource(R.string.game_env_reset_title, profileName)) },
        text = { Text(stringResource(R.string.game_env_reset_detail), fontSize = 13.sp) },
        // Opens on Cancel, so a stray A press never clears a profile.
        confirmButton = {
            FocusText(stringResource(R.string.game_env_reset), colors.error) {
                confirmReset = false
                config?.let { save(it.withEntries(scope, emptyMap())) }
                refocus()
            }
        },
        dismissButton = {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
            FocusText(stringResource(R.string.game_env_cancel), colors.onBackground, Modifier.focusRequester(cancelFocus)) { confirmReset = false }
        },
    )
}

@Composable
private fun ProfileChip(
    host: MenuHost, config: GameEnvironment.Config, games: List<Pair<String, String>>, scope: String, label: String,
    focus: FocusRequester, onPick: (String) -> Unit, onOther: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val open = host.open == "profile"
    Box {
        ValueChip(label, open, modifier = Modifier.widthIn(max = 260.dp).focusRequester(focus)) { host.open = if (open) null else "profile" }
        AnchoredMenu(open, onDismiss = { if (host.open == "profile") host.open = null }, title = stringResource(R.string.game_env_scope)) { first ->
            val others = config.games.keys.filter { id -> games.none { it.first == id } }.sortedBy { it.toLong() }
            val profiles = listOf("" to stringResource(R.string.game_env_shared)) + games +
                others.map { it to resources.getString(R.string.game_env_profile, it) }
            profiles.forEachIndexed { index, (id, name) ->
                val changes = config.entries(id).size
                MenuItem(
                    name, checked = id == scope, focusRequester = if (index == 0) first else null,
                    detail = if (changes > 0) resources.getQuantityString(R.plurals.game_env_changes, changes, changes) else null,
                ) { host.open = null; onPick(id) }
            }
            MenuItem(stringResource(R.string.game_env_other_id), checked = false) { host.open = null; onOther() }
        }
    }
}

/** One variable on one line: its name, where its value comes from, and the value, which opens its menu. */
@Composable
private fun VariableRow(
    host: MenuHost, entry: GameEnvironment.Entry, inherited: GameEnvironment.Entry?,
    onSet: (String?) -> Unit, onRestore: () -> Unit, onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val option = GameEnvironmentOptions.find(entry.name)
    val key = "env:" + entry.name
    val open = host.open == key
    val close = { if (host.open == key) host.open = null }
    val value = entry.value
    val unset = stringResource(R.string.game_env_unset)
    val empty = stringResource(R.string.game_env_empty_value)
    fun shown(text: String?) = when { text == null -> unset; text.isEmpty() -> empty; else -> text }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(if (open) pal.signal.copy(alpha = 0.10f) else Color.Transparent)
            .padding(start = 14.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                entry.name, fontSize = 13.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                color = if (entry.origin == Origin.PROFILE) colors.onBackground else colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            val origin = when (entry.origin) {
                Origin.DEFAULT -> stringResource(R.string.game_env_origin_default)
                Origin.SHARED -> stringResource(R.string.game_env_origin_shared)
                Origin.PROFILE -> null
            }
            if (origin != null) Text(
                origin, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Box {
            ValueChip(shown(value), open, modifier = Modifier.widthIn(max = 220.dp)) { host.open = if (open) null else key }
            AnchoredMenu(open, onDismiss = close, note = option?.let { stringResource(it.detail) }) { first ->
                val choices = option?.choices.orEmpty()
                val separator = option?.separator ?: ','
                val multiple = option?.type == GameEnvironmentOptions.Type.MULTIPLE
                choices.forEachIndexed { index, choice ->
                    val checked = if (multiple) value?.split(separator)?.contains(choice) == true else value == choice
                    MenuItem(choice, checked, focusRequester = if (index == 0) first else null) {
                        if (multiple) onSet(GameEnvironmentOptions.toggle(value.orEmpty(), choice, separator)) else { onSet(choice); close() }
                    }
                }
                MenuItem(stringResource(R.string.game_env_edit_value), checked = false, focusRequester = if (choices.isEmpty()) first else null) {
                    close(); onEdit()
                }
                if (value != null) MenuItem(unset, checked = false, detail = stringResource(R.string.game_env_unset_detail)) { onSet(null); close() }
                if (entry.origin == Origin.PROFILE) {
                    if (inherited != null) MenuItem(stringResource(R.string.game_env_restore), checked = false, detail = shown(inherited.value)) { onRestore(); close() }
                    else MenuItem(stringResource(R.string.game_env_delete), checked = false) { onRestore(); close() }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun ValueDialog(edit: EnvEdit, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val custom = edit.name.isEmpty()
    var name by remember { mutableStateOf(edit.name) }
    var value by remember { mutableStateOf(edit.value) }
    val option = GameEnvironmentOptions.find(name)
    val valid = GameEnvironment.validName(name) && GameEnvironment.validValue(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (custom) stringResource(R.string.game_env_add) else name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (custom) OutlinedTextField(
                    name, { name = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.game_env_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value, { value = it }, singleLine = true, label = { Text(stringResource(R.string.game_env_value)) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (option?.type == GameEnvironmentOptions.Type.NUMBER) KeyboardType.Number else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                option?.let { Text(stringResource(it.detail), fontSize = 12.sp, color = colors.onSurfaceVariant) }
                if (name.isNotEmpty() && !GameEnvironment.supported(name)) Text(stringResource(R.string.game_env_unused), fontSize = 12.sp, color = colors.onSurfaceVariant)
                if (name.isNotEmpty() && !valid) Text(stringResource(R.string.game_env_invalid), fontSize = 12.sp, color = colors.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, value) }, enabled = valid) { Text(stringResource(R.string.game_env_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.game_env_cancel)) } },
    )
}

@Composable
private fun AppIdDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    val valid = id.isNotEmpty() && GameEnvironment.validScope(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            OutlinedTextField(
                id, { id = it.filter(Char::isDigit).take(10) }, singleLine = true,
                label = { Text(stringResource(R.string.game_env_app_id)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onOpen(id) }, enabled = valid) { Text(stringResource(R.string.game_env_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.game_env_cancel)) } },
    )
}
