/**
 * Gramtone (Metrolist fork) - Gramtone Vault (Telegram) screens
 * Licensed under GPL-3.0
 */

package com.metrolist.music.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.HideOnScrollFAB
import com.metrolist.music.ui.screens.Screens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** Which side the Home tab shows. Gramtone Vault is the main one every time the app opens. */
object HomeSource {
    private val _vault = MutableStateFlow(true)
    val vault: StateFlow<Boolean> = _vault
    fun toggle() { _vault.value = !_vault.value }
}

@Composable
fun rememberHomeIsVault(): Boolean = HomeSource.vault.collectAsState().value

/** The Vault tab. */
@Composable
fun VaultScreen() {
    TelegramLibraryScreen()
}

// ---------------------------------------------------------------------------------------------
// Connect Telegram
// ---------------------------------------------------------------------------------------------

/** Icon, title and the short privacy note shared by every step of the connect flow. */
@Composable
private fun VaultConnectHeader() {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.gt_link), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Spacer(Modifier.height(16.dp))
    Text("Connect Telegram", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Generate your api_id and api_hash at my.telegram.org, but keep the hash strictly private and never share it publicly to protect your account's quota.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
}

/** What Home and Vault show before Telegram is connected: the card and a Start button. */
@Composable
fun VaultConnectCard(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VaultConnectHeader()
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    }
}

private fun defaultCountry(): TgCountry {
    val iso = java.util.Locale.getDefault().country
    return TG_COUNTRIES.firstOrNull { it.iso == iso } ?: TG_COUNTRIES.first { it.iso == "US" }
}

/** The step-by-step login: api keys, phone number with country code, login code, two-step password. */
@Composable
internal fun VaultConnect(prefs: SharedPreferences) {
    val context = LocalContext.current
    val auth by TelegramClient.auth.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    var apiId by remember { mutableStateOf(prefs.getString("id", "") ?: "") }
    var apiHash by remember { mutableStateOf(prefs.getString("hash", "") ?: "") }
    var phone by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(defaultCountry()) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pickCountry by remember { mutableStateOf(false) }
    val needKeys = auth == TelegramClient.AuthState.NOT_STARTED || auth == TelegramClient.AuthState.ERROR

    Column(
        Modifier.fillMaxSize().padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VaultConnectHeader()
        when {
            needKeys && !started -> {
                Button(onClick = { started = true }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            }
            needKeys -> {
                OutlinedTextField(
                    value = apiId,
                    onValueChange = { apiId = it },
                    label = { Text("api_id") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it },
                    label = { Text("api_hash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val idNum = apiId.trim().toIntOrNull()
                        if (idNum != null && apiHash.isNotBlank()) {
                            prefs.edit().putString("id", apiId.trim()).putString("hash", apiHash.trim()).apply()
                            TelegramClient.start(context.applicationContext, idNum, apiHash.trim())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Submit") }
            }
            auth == TelegramClient.AuthState.WAIT_PHONE -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickCountry = true }) { Text("${country.flag} ${country.dial}") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val national = phone.filter { it.isDigit() }.trimStart('0')
                        if (national.isNotEmpty()) TelegramClient.submitPhone(country.dial + national)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send Code") }
            }
            auth == TelegramClient.AuthState.WAIT_CODE -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Login code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { TelegramClient.submitCode(code.trim()); code = "" }, modifier = Modifier.fillMaxWidth()) { Text("Submit") }
            }
            auth == TelegramClient.AuthState.WAIT_PASSWORD -> {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Two-step password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { TelegramClient.submitPassword(password); password = "" }, modifier = Modifier.fillMaxWidth()) { Text("Submit") }
            }
            else -> Text("Connected", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (pickCountry) {
        CountryDialog(
            onPick = { country = it; pickCountry = false },
            onDismiss = { pickCountry = false },
        )
    }
}

/** Country list with search; tap a country to use its calling code. */
@Composable
private fun CountryDialog(onPick: (TgCountry) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val list = remember(q) {
        if (q.isEmpty()) TG_COUNTRIES
        else TG_COUNTRIES.filter { it.name.lowercase().contains(q) || it.dial.contains(q) || it.iso.lowercase() == q }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Country code") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(list, key = { it.iso }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.flag, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.dial, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Home (Gramtone Vault side)
// ---------------------------------------------------------------------------------------------

@Composable
private fun VaultSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** Home while the Gramtone Vault is selected. YouTube Music's own Home stays as it was. */
@Composable
fun VaultHomeScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tg_poc", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        TelegramCatalog.init(context)
        TelegramClient.autoStart(context)
    }
    val auth by TelegramClient.auth.collectAsState()
    val tracks by TelegramCatalog.tracks.collectAsState()
    val database = LocalDatabase.current
    val playCounts by database.playCounts().collectAsState(initial = emptyList())
    val playerConnection = LocalPlayerConnection.current
    val chatTitle = remember { prefs.getString("chat_title", null) ?: "Gramtone Vault" }
    val connected = auth == TelegramClient.AuthState.READY && prefs.getLong("chat_id", 0L) != 0L
    var seed by rememberSaveable { mutableStateOf(0) }
    val lazyListState = rememberLazyListState()
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()

    if (!connected) {
        VaultConnectCard(onStart = { navController.navigate(Screens.Vault.route) { launchSingleTop = true } })
        return
    }

    fun play(list: List<TgTrack>, index: Int, title: String) {
        if (list.isNotEmpty()) {
            playerConnection?.playQueue(ListQueue(title = title, items = list.map { it.toMediaItem() }, startIndex = index))
        }
    }

    val plays = remember(playCounts) { playCounts.associate { it.songId to it.plays } }
    val newest = remember(tracks) { tracks.sortedByDescending { it.messageId } }
    val keep = newest.take(6)
    val heavy = remember(tracks, plays) {
        tracks.filter { (plays[it.id] ?: 0) > 0 }.sortedByDescending { plays[it.id] ?: 0 }.take(20)
    }
    val projects = remember(tracks) {
        buildAlbums(tracks).sortedByDescending { a -> a.tracks.maxOf { it.messageId } }.take(30)
    }
    val discover = remember(tracks, seed) { tracks.shuffled(Random(seed + 1L)).take(20) }
    val artistsMix = remember(tracks, seed) { buildArtists(tracks).shuffled(Random(seed + 2L)).take(15) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = lazyListState, contentPadding = insets) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep listening", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(chatTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Open the Vault tab: your tracks appear as they are scanned.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        keep.chunked(2).forEachIndexed { rowIdx, pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEachIndexed { colIdx, t ->
                                    Row(
                                        Modifier.weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { play(newest, rowIdx * 2 + colIdx, "Gramtone Vault") },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TgCover(t.coverPath, 48.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (heavy.isNotEmpty()) {
                    item { VaultSectionTitle("In heavy rotation") }
                    item { TrackRow(heavy) { i -> play(heavy, i, "In heavy rotation") } }
                }
                item { VaultSectionTitle("Recently added") }
                item { TrackRow(newest.take(20)) { i -> play(newest, i, "Recently added") } }
                if (projects.isNotEmpty()) {
                    item { VaultSectionTitle("Projects") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(projects, key = { it.key }) { a ->
                                Column(Modifier.width(130.dp).clickable { play(a.tracks, 0, a.title) }) {
                                    TgCover(a.coverPath, 130.dp)
                                    Spacer(Modifier.height(6.dp))
                                    Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                item { VaultSectionTitle("Suggested for you") }
                item { TrackRow(discover) { i -> play(discover, i, "Suggested for you") } }
                if (artistsMix.isNotEmpty()) {
                    item { VaultSectionTitle("Artists") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(artistsMix, key = { it.name }) { a ->
                                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    TgArtistAvatar(a.name, a.coverPath, 96.dp)
                                    Spacer(Modifier.height(6.dp))
                                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 96.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { seed += 1 }) { Text("Get new suggestions") }
                    }
                }
            }
        }
        HideOnScrollFAB(
            visible = tracks.isNotEmpty(),
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            onClick = { play(tracks.shuffled(), 0, "Gramtone Vault") },
            onRecognitionClick = { navController.navigate("recognition") },
        )
    }
}

@Composable
private fun TrackRow(list: List<TgTrack>, onPlay: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val t = list[i]
            Column(Modifier.width(130.dp).clickable { onPlay(i) }) {
                TgCover(t.coverPath, 130.dp)
                Spacer(Modifier.height(6.dp))
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
