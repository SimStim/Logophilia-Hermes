package eu.logophilia.hermes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.logophilia.hermes.BuildConfig
import eu.logophilia.hermes.R
import eu.logophilia.hermes.api.GreetingResult
import eu.logophilia.hermes.sync.Category
import eu.logophilia.hermes.sync.ContentLayout
import eu.logophilia.hermes.sync.SyncCoordinator
import eu.logophilia.hermes.sync.SyncOutcome
import eu.logophilia.hermes.sync.SyncPhase
import eu.logophilia.hermes.sync.SyncStatus
import eu.logophilia.hermes.sync.WifiIndicator
import eu.logophilia.hermes.ui.theme.IndicatorAmber
import eu.logophilia.hermes.ui.theme.IndicatorBlue
import eu.logophilia.hermes.ui.theme.IndicatorGreen
import java.io.File
import java.text.DateFormat
import java.util.Date
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Section(val labelRes: Int, val iconRes: Int) {
    Home(R.string.tab_home, R.drawable.ic_tab_home),
    Library(R.string.tab_library, R.drawable.ic_tab_library),
    Sync(R.string.tab_sync, R.drawable.ic_tab_sync),
    About(R.string.tab_about, R.drawable.ic_tab_about),
}

private fun Category.labelRes(): Int = when (this) {
    Category.Books -> R.string.category_books
    Category.Music -> R.string.category_music
    Category.Documents -> R.string.category_documents
    Category.Other -> R.string.category_other
}

/**
 * Application shell with the four destinations from SPECIFICATION.md §8.
 * All state flows from the [SyncCoordinator]; the filesystem is only read
 * off the main thread.
 */
@Composable
fun HermesApp(coordinator: SyncCoordinator, contentLayout: ContentLayout) {
    var current by rememberSaveable { mutableStateOf(Section.Home) }
    var libraryCategory by rememberSaveable { mutableStateOf(Category.Books.name) }
    // §8: the folder currently browsed inside the Library — hoisted here so
    // Home's recently-added folder items can open the Library one level deep.
    var libraryBrowse by remember { mutableStateOf<LibraryItem.FolderItem?>(null) }
    val status by coordinator.status.collectAsState()
    val wifi by coordinator.wifi.collectAsState()
    val wifiDetail by coordinator.wifiDetail.collectAsState()

    var showWifiPopup by remember { mutableStateOf(false) }
    var showSyncPopup by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val opener = remember { FileOpener(context) }
    val scope = rememberCoroutineScope()
    var pendingMissing by remember { mutableStateOf<MissingOpen?>(null) }

    fun open(file: File) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { opener.open(file) }
            val missing = outcome as? OpenOutcome.MissingHandler
            if (missing != null) pendingMissing = MissingOpen(file, missing)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { section ->
                    NavigationBarItem(
                        selected = current == section,
                        onClick = { current = section },
                        icon = {
                            // §8: the bottom navigation's Sync icon plays the
                            // rotation animation while a synchronization runs
                            // and stays neutral otherwise, so sync activity is
                            // visible from every tab.
                            val rotation = rememberSyncRotation(
                                animate = section == Section.Sync && status.running,
                            )
                            Icon(
                                painterResource(section.iconRes),
                                contentDescription = stringResource(section.labelRes),
                                modifier = Modifier.rotate(rotation),
                            )
                        },
                        label = { Text(stringResource(section.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val category = Category.valueOf(libraryCategory)
        // Every category selection lands at the category's top level (§8) —
        // the chips, the Home quick access, and Home's recently added all
        // route through this.
        val selectCategory: (Category) -> Unit = { selected ->
            libraryBrowse = null
            libraryCategory = selected.name
        }
        when (current) {
            Section.Home -> HomeScreen(
                coordinator = coordinator,
                contentLayout = contentLayout,
                status = status,
                wifi = wifi,
                onWifiTap = { showWifiPopup = true },
                onOpenCategory = { selected ->
                    selectCategory(selected)
                    current = Section.Library
                },
                onOpenRecent = { category, item ->
                    // §8 Home: a recent file opens in an external application
                    // (§7); a recent archive opens the Library browsed one
                    // level into it.
                    when (item) {
                        is LibraryItem.FileItem -> open(item.file)
                        is LibraryItem.FolderItem -> {
                            libraryCategory = category.name
                            libraryBrowse = item
                            current = Section.Library
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            Section.Library -> LibraryScreen(
                contentLayout = contentLayout,
                selected = category,
                browse = libraryBrowse,
                status = status,
                onSelect = selectCategory,
                onBrowse = { libraryBrowse = it },
                onOpenFile = { file -> open(file) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            Section.Sync -> SyncScreen(
                coordinator = coordinator,
                status = status,
                onSyncTap = { showSyncPopup = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            Section.About -> AboutScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (showWifiPopup) {
        AlertDialog(
            onDismissRequest = { showWifiPopup = false },
            title = { Text(stringResource(R.string.popup_wifi_title)) },
            text = { Text(wifiDetailText(wifi, wifiDetail)) },
            confirmButton = {
                TextButton(onClick = { showWifiPopup = false }) {
                    Text(stringResource(R.string.popup_ok))
                }
            },
        )
    }
    if (showSyncPopup) {
        AlertDialog(
            onDismissRequest = { showSyncPopup = false },
            title = { Text(stringResource(R.string.popup_sync_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncStateLines(status = status, headingStyle = MaterialTheme.typography.bodyMedium)
                    val failure = status.lastOutcome as? SyncOutcome.Failure
                    if (failure != null) {
                        Text(
                            stringResource(
                                R.string.sync_detail_error,
                                failure.message,
                            ),
                        )
                    }
                    val tick = rememberTickSeconds()
                    val remaining = remember(tick) { coordinator.cooldownRemainingMillis() }
                    if (remaining > 0) {
                        Text(stringResource(R.string.sync_cooldown, formatCooldown(remaining)))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncPopup = false }) {
                    Text(stringResource(R.string.popup_ok))
                }
            },
        )
    }
    pendingMissing?.let { missing ->
        MissingHandlerDialog(
            missing = missing,
            onDismiss = { pendingMissing = null },
            // §7: offer to retry after installation — the intent is resolved
            // again; when it now starts, the dialog goes away.
            onRetry = { open(missing.file) },
        )
    }
}

/** A file whose open attempt found no installed application (§7). */
private data class MissingOpen(val file: File, val outcome: OpenOutcome.MissingHandler)

/**
 * §7 Missing-handler handling: a concise installation suggestion, a direct
 * Google Play link where an appropriate application exists (otherwise a Play
 * search), and a retry after installation. Tapping Install keeps the dialog
 * open on purpose so the user can come back to Retry.
 */
@Composable
private fun MissingHandlerDialog(
    missing: MissingOpen,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_missing_title)) },
        text = { Text(missingHandlerText(missing.outcome.suggestion)) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.open_cancel))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.open_retry))
                }
                TextButton(
                    onClick = {
                        val url = missing.outcome.suggestion?.playUrl
                            ?: OpenWith.searchUrlFor(missing.outcome.mimeType)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                ) {
                    Text(installButtonText(missing.outcome.suggestion))
                }
            }
        },
    )
}

@Composable
private fun missingHandlerText(suggestion: InstallSuggestion?): String =
    if (suggestion != null) {
        stringResource(R.string.open_missing_with_suggestion, stringResource(suggestion.appNameRes))
    } else {
        stringResource(R.string.open_missing_generic)
    }

@Composable
private fun installButtonText(suggestion: InstallSuggestion?): String =
    if (suggestion != null) {
        stringResource(R.string.open_install, stringResource(suggestion.appNameRes))
    } else {
        stringResource(R.string.open_search)
    }

/**
 * An ICU plural as display text (§8 User-facing copy): "1 document" vs
 * "N documents". The count is passed both as the quantity selector and as the
 * pattern argument.
 */
@Composable
private fun quantityString(pluralsRes: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(pluralsRes, count, count)

/** Re-composes once per second while its screen is visible. */
@Composable
private fun rememberTickSeconds(): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    return tick
}

private fun formatCooldown(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun wifiDetailText(wifi: WifiIndicator, detail: GreetingResult?): String = when (wifi) {
    WifiIndicator.NEUTRAL -> stringResource(R.string.wifi_desc_neutral)
    WifiIndicator.RED -> stringResource(R.string.wifi_detail_red)
    WifiIndicator.AMBER -> stringResource(
        R.string.wifi_detail_amber,
        detail?.let { result ->
            when (result) {
                GreetingResult.UNREACHABLE -> stringResource(R.string.wifi_reason_unreachable)
                GreetingResult.AUTH_REJECTED -> stringResource(R.string.wifi_reason_auth)
                else -> stringResource(R.string.wifi_reason_server)
            }
        } ?: stringResource(R.string.wifi_reason_unreachable),
    )
    WifiIndicator.GREEN -> stringResource(R.string.wifi_detail_green)
}

/**
 * The synchronization state as displayed text (§8). While running it is a
 * single line; after a successful attempt it reads as a vertical list —
 * "Synchronized" on its own line, then the new/updated/removed counts, one
 * ICU plural per line, like the Home page's lists (§8 Sync). With
 * [centered] every line fills the width and centers, so even a wrapped line
 * stays centered on the Sync page.
 */
@Composable
private fun SyncStateLines(
    status: SyncStatus,
    headingStyle: TextStyle,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val lineModifier = if (centered) modifier.fillMaxWidth() else modifier
    val alignment = if (centered) TextAlign.Center else null
    if (status.running) {
        Text(
            text = when (status.phase) {
                SyncPhase.MANIFEST_REQUESTED -> stringResource(R.string.sync_state_contacting)
                SyncPhase.TRANSFERRING -> stringResource(R.string.sync_state_receiving)
                SyncPhase.COMMITTING -> stringResource(R.string.sync_state_committing)
                null -> stringResource(R.string.sync_state_contacting)
            },
            style = headingStyle,
            textAlign = alignment,
            modifier = lineModifier,
        )
        return
    }
    when (val outcome = status.lastOutcome) {
        is SyncOutcome.Success -> Column(
            modifier = lineModifier,
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.sync_state_success), style = headingStyle, textAlign = alignment)
            Text(
                quantityString(R.plurals.sync_count_new, outcome.added),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = alignment,
            )
            Text(
                quantityString(R.plurals.sync_count_updated, outcome.updated),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = alignment,
            )
            Text(
                quantityString(R.plurals.sync_count_removed, outcome.removed),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = alignment,
            )
        }
        is SyncOutcome.Failure -> Text(
            stringResource(R.string.sync_state_failure),
            style = headingStyle,
            textAlign = alignment,
            modifier = lineModifier,
        )
        else -> Text(
            stringResource(R.string.sync_state_idle),
            style = headingStyle,
            textAlign = alignment,
            modifier = lineModifier,
        )
    }
}

@Composable
private fun syncStateDescription(status: SyncStatus): String = when {
    status.running -> stringResource(R.string.sync_desc_running)
    status.lastOutcome is SyncOutcome.Failure -> stringResource(R.string.sync_desc_failed)
    status.lastOutcome is SyncOutcome.Success -> stringResource(R.string.sync_desc_done)
    else -> stringResource(R.string.sync_desc_idle)
}

// ---------------------------------------------------------------------------
// Home (§8)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    coordinator: SyncCoordinator,
    contentLayout: ContentLayout,
    status: SyncStatus,
    wifi: WifiIndicator,
    onWifiTap: () -> Unit,
    onOpenCategory: (Category) -> Unit,
    onOpenRecent: (Category, LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Content changes only through synchronization; every status change —
    // including each per-file phase signal (§5) — re-reads, so recently added
    // fills live while a synchronization runs.
    val home by produceState<HomeData?>(null, contentLayout, status) {
        value = withContext(Dispatchers.IO) {
            val counts = LibraryContent.counts(contentLayout)
            HomeData(
                counts.books,
                counts.albums,
                counts.documents,
                counts.other,
                LibraryContent.recent(contentLayout),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(
                if (isSystemInDarkTheme()) R.drawable.app_logo_dark else R.drawable.app_logo_light,
            ),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(120.dp),
        )
        Spacer(Modifier.height(8.dp))
        // §8: Home shows the Wi-Fi indicator as a large symbol — the same
        // size as the synchronization symbol on the Sync page — and no
        // separate sync symbol and no colored status circle: sync status
        // lives in the bottom navigation's animated Sync icon and on the
        // Sync page; each indicator has exactly one visual home.
        WifiSymbol(state = wifi, onClick = onWifiTap)
        Spacer(Modifier.height(16.dp))
        // §8 Home: the counts as a vertical list, one line per category.
        home?.let { data ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = quantityString(R.plurals.home_count_books, data.books),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = quantityString(R.plurals.home_count_albums, data.albums),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = quantityString(R.plurals.home_count_documents, data.documents),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = quantityString(R.plurals.home_count_other, data.other),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } ?: Text(
            text = stringResource(R.string.home_nothing_yet),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Category.entries.forEach { category ->
                FilterChip(
                    selected = false,
                    onClick = { onOpenCategory(category) },
                    label = { Text(stringResource(category.labelRes())) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val recent = home?.recent.orEmpty()
        if (recent.isNotEmpty()) {
            Text(
                text = stringResource(R.string.home_recently_added),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn {
                items(recent) { (category, item) ->
                    // §8 Home: recently-added items are tappable — a file opens
                    // in an external application (§7), an archive opens the
                    // Library browsed one level into it.
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecent(category, item) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private data class HomeData(
    val books: Int,
    val albums: Int,
    val documents: Int,
    val other: Int,
    val recent: List<Pair<Category, LibraryItem>>,
)

// ---------------------------------------------------------------------------
// Library (§8)
// ---------------------------------------------------------------------------

@Composable
private fun LibraryScreen(
    contentLayout: ContentLayout,
    selected: Category,
    browse: LibraryItem.FolderItem?,
    status: SyncStatus,
    onSelect: (Category) -> Unit,
    onBrowse: (LibraryItem.FolderItem?) -> Unit,
    onOpenFile: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    // §8: the browsed directory — an extracted archive at the top level, or a
    // subdirectory inside one — is hoisted to the app shell, so Home's
    // recently-added items can open the Library one level deep.
    Column(modifier = modifier.padding(16.dp)) {
        // Chips centered like on the Home page, with the same 8 dp spacing
        // between them (§8). Every tap returns to the chosen category's top
        // level — including a tap on the already-active chip — so the top row
        // always answers while an archive is browsed.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Category.entries.forEach { category ->
                FilterChip(
                    selected = category == selected,
                    onClick = { onSelect(category) },
                    label = { Text(stringResource(category.labelRes())) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Keyed on the whole status: every phase signal re-reads the content
        // directories, so the Library fills while a synchronization runs.
        val items by produceState(
            initialValue = emptyList<LibraryItem>(),
            contentLayout,
            selected,
            browse,
            status,
        ) {
            value = withContext(Dispatchers.IO) {
                val inside = browse?.directory
                if (inside == null) {
                    LibraryContent.topLevel(contentLayout, selected)
                } else {
                    LibraryContent.contents(inside)
                }
            }
        }
        if (browse != null) {
            // The back affordance: an arrow, left-justified with the list, on
            // a full-width row so the tap target is generous. It pops exactly
            // one level (§8 Library); tapping the active category chip above
            // returns to the top level from any depth in one tap.
            val current = browse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (current != null) {
                            onBrowse(LibraryContent.parent(current, contentLayout, selected))
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.library_back),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (items.isEmpty()) {
            // Same vertical offset as a list row, so an empty category lines
            // up with the first row of a populated one.
            Text(
                text = stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn {
                items(items) { item ->
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                when (item) {
                                    is LibraryItem.FolderItem -> onBrowse(item)
                                    is LibraryItem.FileItem -> onOpenFile(item.file)
                                }
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sync (§8)
// ---------------------------------------------------------------------------

@Composable
private fun SyncScreen(
    coordinator: SyncCoordinator,
    status: SyncStatus,
    onSyncTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tick = rememberTickSeconds()
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SyncGlyph(
            status = status,
            onClick = onSyncTap,
        )
        Spacer(Modifier.height(12.dp))
        // The success summary reads as a vertical list (§8 Sync); the page's
        // centering keeps every line centered, including wrapped ones.
        SyncStateLines(
            status = status,
            headingStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            centered = true,
        )
        Spacer(Modifier.height(8.dp))
        val lastAttempt = coordinator.lastSyncAttemptMillis()
        Text(
            text = lastAttempt?.let {
                stringResource(
                    R.string.sync_last_attempt,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(it)),
                )
            } ?: stringResource(R.string.sync_never_attempted),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        (status.lastOutcome as? SyncOutcome.Failure)?.let { failure ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val remaining = remember(tick) { coordinator.cooldownRemainingMillis() }
        if (remaining > 0) {
            Text(
                text = stringResource(R.string.sync_cooldown, formatCooldown(remaining)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !status.running && remaining <= 0,
            onClick = { coordinator.requestSync() },
        ) {
            Text(stringResource(R.string.sync_manual))
        }
    }
}

// ---------------------------------------------------------------------------
// About (§8) — there are no settings; the app follows the system theme (§2).
// ---------------------------------------------------------------------------

@Composable
private fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(
                if (isSystemInDarkTheme()) R.drawable.app_logo_dark else R.drawable.app_logo_light,
            ),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(12.dp))
        // §8 About: the short name under the logophilia logo — the launcher
        // keeps the full "Logophilia Hermes" (strings.xml app_name).
        Text(text = stringResource(R.string.about_name), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Status indicators (§8)
// ---------------------------------------------------------------------------

// The fixed indicator colors (green/amber/blue) live in ui/theme/IndicatorColors.kt
// — the single place to tune their exact hex values.

/**
 * The Wi-Fi indicator (§8): the Wi-Fi symbol on Home, rendered at the same
 * size as the Sync page's synchronization symbol; its color encodes
 * reachability — red without a validated unmetered network, amber when the
 * greeting did not answer successfully, green on success, neutral only
 * before the very first probe. Tapping it opens the detail popup.
 */
@Composable
private fun WifiSymbol(state: WifiIndicator, onClick: () -> Unit) {
    val color = when (state) {
        WifiIndicator.NEUTRAL -> MaterialTheme.colorScheme.outline
        WifiIndicator.RED -> MaterialTheme.colorScheme.error
        WifiIndicator.AMBER -> IndicatorAmber
        WifiIndicator.GREEN -> IndicatorGreen
    }
    val description = when (state) {
        WifiIndicator.NEUTRAL -> stringResource(R.string.wifi_desc_neutral)
        WifiIndicator.RED -> stringResource(R.string.wifi_desc_red)
        WifiIndicator.AMBER -> stringResource(R.string.wifi_desc_amber)
        WifiIndicator.GREEN -> stringResource(R.string.wifi_desc_green)
    }
    Icon(
        painter = painterResource(R.drawable.ic_wifi),
        contentDescription = description,
        tint = color,
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
    )
}

/**
 * The synchronization indicator: the two-arrow circular sync symbol (§8),
 * the only color-coded sync indicator in the app — blue while the manifest
 * request waits, green animated while content flows, green static on success,
 * red on failure, neutral before the first attempt. Tapping it opens the
 * sync detail popup.
 */
@Composable
private fun SyncGlyph(status: SyncStatus, onClick: () -> Unit) {
    val color = when {
        status.running && status.phase == SyncPhase.MANIFEST_REQUESTED -> IndicatorBlue
        status.running -> IndicatorGreen
        status.lastOutcome is SyncOutcome.Success -> IndicatorGreen
        status.lastOutcome is SyncOutcome.Failure -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val animated = status.running && status.phase != SyncPhase.MANIFEST_REQUESTED
    Icon(
        painter = painterResource(R.drawable.ic_tab_sync),
        contentDescription = syncStateDescription(status),
        tint = color,
        modifier = Modifier
            .size(48.dp)
            .rotate(rememberSyncRotation(animated))
            .clickable(onClick = onClick),
    )
}

/** Continuous 0→360° rotation while [animate] holds; 0 otherwise (§8). */
@Composable
private fun rememberSyncRotation(animate: Boolean): Float =
    if (animate) {
        rememberInfiniteTransition(label = "sync-rotation").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sync-angle",
        ).value
    } else {
        0f
    }
