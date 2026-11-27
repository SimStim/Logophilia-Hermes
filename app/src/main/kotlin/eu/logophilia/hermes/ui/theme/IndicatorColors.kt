package eu.logophilia.hermes.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The fixed colors of the status indicators (SPECIFICATION.md §8 Status
 * indicators) — the one place to tune their exact appearance. Edit a hex
 * value, then reinstall: `./gradlew installDebug`.
 *
 * - [IndicatorGreen] — the synchronization symbol while content flows and
 *   after a successful attempt, and the Wi-Fi symbol when the server is
 *   reachable.
 * - [IndicatorAmber] — the Wi-Fi symbol when the greeting did not answer
 *   successfully on an unmetered network.
 * - [IndicatorBlue] — the synchronization symbol while the manifest request
 *   waits for the server ("Contacting server…").
 *
 * The failure red and the neutral placeholder are deliberately not fixed
 * here: they use MaterialTheme.colorScheme.error and .outline so they always
 * contrast with the surrounding light/dark theme. Every other color in the
 * app (backgrounds, text, chips, surfaces) follows the Material 3 color
 * scheme — on Android 12+ derived from the system wallpaper (HermesTheme).
 */
val IndicatorGreen = Color(0xFF36482e)
val IndicatorAmber = Color(0xFFc8b273)
val IndicatorBlue = Color(0xFF416392)
