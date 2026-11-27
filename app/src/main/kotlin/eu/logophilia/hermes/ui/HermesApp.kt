package eu.logophilia.hermes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                            Icon(
                                painterResource(section.iconRes),
                                contentDescription = stringResource(section.labelRes),
                            )
                        },
                        label = { Text(stringResource(section.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val category = Category.valueOf(libraryCategory)
        when (current) {
            Section.Home -> HomeScreen(
                coordinator = coordinator,
                contentLayout = contentLayout,
                status = status,
                wifi = wifi,
                onWifiTap = { showWifiPopup = true },
                onSyncTap = { showSyncPopup = true },
                onOpenCategory = {
                    libraryCategory = it.name
                    current = Section.Library
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            Section.Library -> LibraryScreen(
                contentLayout = contentLayout,
                selected = category,
                status = status,
                onSelect = { libraryCategory = it.name },
                onOpenItem = { item -> open(item.file) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            Section.Sync -> SyncScreen(
                coordinator = coordinator,
                status = status,
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
                    Text(syncStateText(status))
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
        stringResource(R.string.open_missing_with_suggestion, suggestion.appName)
    } else {
        stringResource(R.string.open_missing_generic)
    }

@Composable
private fun installButtonText(suggestion: InstallSuggestion?): String =
    if (suggestion != null) {
        stringResource(R.string.open_install, suggestion.appName)
    } else {
        stringResource(R.string.open_search)
    }

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
                GreetingResult.UNREACHABLE -> "unreachable"
                GreetingResult.AUTH_REJECTED -> "authentication rejected"
                else -> "server error"
            }
        } ?: "unreachable",
    )
    WifiIndicator.GREEN -> stringResource(R.string.wifi_detail_green)
}

@Composable
private fun syncStateText(status: SyncStatus): String {
    if (status.running) {
        return when (status.phase) {
            SyncPhase.MANIFEST_REQUESTED -> stringResource(R.string.sync_state_contacting)
            SyncPhase.TRANSFERRING -> stringResource(R.string.sync_state_receiving)
            SyncPhase.COMMITTING -> stringResource(R.string.sync_state_committing)
            null -> stringResource(R.string.sync_state_contacting)
        }
    }
    return when (val outcome = status.lastOutcome) {
        is SyncOutcome.Success -> stringResource(
            R.string.sync_state_success,
            outcome.added,
            outcome.updated,
            outcome.removed,
        )
        is SyncOutcome.Failure -> stringResource(R.string.sync_state_failure)
        else -> stringResource(R.string.sync_state_idle)
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
    onSyncTap: () -> Unit,
    onOpenCategory: (Category) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Content changes only through synchronization; re-read after each attempt.
    val home by produceState<HomeData?>(null, contentLayout, status.lastOutcome) {
        value = withContext(Dispatchers.IO) {
            val (books, albums, documents) = LibraryContent.counts(contentLayout)
            HomeData(books, albums, documents, LibraryContent.recent(contentLayout))
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
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WifiIndicatorDot(
                state = wifi,
                onClick = onWifiTap,
            )
            SyncGlyph(
                status = status,
                large = false,
                onClick = onSyncTap,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = home?.let {
                stringResource(R.string.home_counts, it.books, it.albums, it.documents)
            } ?: stringResource(R.string.home_nothing_yet),
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
                modifier = Modifier.align(Alignment.Start),
            )
            LazyColumn {
                items(recent) { (category, item) ->
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
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
    val recent: List<Pair<Category, LibraryItem>>,
)

// ---------------------------------------------------------------------------
// Library (§8)
// ---------------------------------------------------------------------------

@Composable
private fun LibraryScreen(
    contentLayout: ContentLayout,
    selected: Category,
    status: SyncStatus,
    onSelect: (Category) -> Unit,
    onOpenItem: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { category ->
                FilterChip(
                    selected = category == selected,
                    onClick = { onSelect(category) },
                    label = { Text(stringResource(category.labelRes())) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val items by produceState(
            initialValue = emptyList<LibraryItem>(),
            contentLayout,
            selected,
            status.lastOutcome,
        ) {
            value = withContext(Dispatchers.IO) { LibraryContent.items(contentLayout, selected) }
        }
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn {
                items(items) { item ->
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenItem(item) }
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
            large = true,
            onClick = {},
        )
        Spacer(Modifier.height(12.dp))
        Text(text = syncStateText(status), style = MaterialTheme.typography.titleMedium)
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
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
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

private val IndicatorGreen = Color(0xFF2E7D32)
private val IndicatorAmber = Color(0xFFF9A825)
private val IndicatorBlue = Color(0xFF1976D2)

/** The Wi-Fi indicator: a compact dot whose color encodes reachability (§8). */
@Composable
private fun WifiIndicatorDot(state: WifiIndicator, onClick: () -> Unit) {
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
    Box(
        modifier = Modifier
            .size(16.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

/**
 * The synchronization indicator: the two-arrow circular sync symbol (§8).
 * Blue while the manifest request waits, green animated while content flows,
 * green static on success, red on failure, neutral before the first attempt.
 */
@Composable
private fun SyncGlyph(status: SyncStatus, large: Boolean, onClick: () -> Unit) {
    val color = when {
        status.running && status.phase == SyncPhase.MANIFEST_REQUESTED -> IndicatorBlue
        status.running -> IndicatorGreen
        status.lastOutcome is SyncOutcome.Success -> IndicatorGreen
        status.lastOutcome is SyncOutcome.Failure -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val animated = status.running && status.phase != SyncPhase.MANIFEST_REQUESTED
    val rotation = if (animated) {
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
    val size = if (large) 48.dp else 24.dp
    Icon(
        painter = painterResource(R.drawable.ic_tab_sync),
        contentDescription = syncStateDescription(status),
        tint = color,
        modifier = Modifier
            .size(size)
            .rotate(rotation)
            .clickable(onClick = onClick),
    )
}
