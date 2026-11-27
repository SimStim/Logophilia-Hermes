package eu.logophilia.hermes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.logophilia.hermes.R

private enum class Section(val labelRes: Int, val icon: ImageVector) {
    Home(R.string.tab_home, Icons.Filled.Home),
    Library(R.string.tab_library, Icons.AutoMirrored.Filled.List),
    Sync(R.string.tab_sync, Icons.Filled.Refresh),
    About(R.string.tab_about, Icons.Filled.Info),
}

/**
 * Application shell: bottom navigation with the four destinations from
 * SPECIFICATION.md §8. Placeholder screens only — synchronization, the
 * library and file opening arrive in later milestones.
 */
@Composable
fun HermesApp() {
    var current by rememberSaveable { mutableStateOf(Section.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { section ->
                    NavigationBarItem(
                        selected = current == section,
                        onClick = { current = section },
                        icon = {
                            Icon(section.icon, contentDescription = stringResource(section.labelRes))
                        },
                        label = { Text(stringResource(section.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (current) {
                Section.Home -> HomePlaceholder()
                else -> PlaceholderScreen(stringResource(current.labelRes))
            }
        }
    }
}

@Composable
private fun HomePlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Bundled Logophilia logo; the light/dark variants (art/) switch with
        // the system theme (SPECIFICATION.md §8, Visual identity).
        Image(
            painter = painterResource(
                if (isSystemInDarkTheme()) R.drawable.app_logo_dark else R.drawable.app_logo_light,
            ),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(160.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )
        PlaceholderText()
    }
}

@Composable
private fun PlaceholderScreen(section: String) {
    PlaceholderText(section)
}

@Composable
private fun PlaceholderText(section: String = "") {
    Text(
        text = stringResource(R.string.shell_placeholder, section),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}
