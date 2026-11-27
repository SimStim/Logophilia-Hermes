package eu.logophilia.hermes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import eu.logophilia.hermes.R

/**
 * The bundled variable fonts (§8 Visual identity): **EB Garamond** for body
 * text and **Comfortaa** for headings — latin subsets with their `wght` axes
 * intact, so every weight below is a true instance of the same file. The OFL
 * license files ship in the APK (`app/src/main/assets/licenses/`); no
 * user-side font installation is involved.
 */
private fun variableFont(resId: Int, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
    Font(
        resId = resId,
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

private val EBGaramond = FontFamily(
    variableFont(R.font.ebgaramond, FontWeight.Normal),
    variableFont(R.font.ebgaramond, FontWeight.Medium),
    variableFont(R.font.ebgaramond, FontWeight.SemiBold),
    variableFont(R.font.ebgaramond, FontWeight.Bold),
    variableFont(R.font.ebgaramond_italic, FontWeight.Normal, FontStyle.Italic),
    variableFont(R.font.ebgaramond_italic, FontWeight.Bold, FontStyle.Italic),
)

private val Comfortaa = FontFamily(
    variableFont(R.font.comfortaa, FontWeight.Normal),
    variableFont(R.font.comfortaa, FontWeight.Medium),
    variableFont(R.font.comfortaa, FontWeight.SemiBold),
    variableFont(R.font.comfortaa, FontWeight.Bold),
)

/**
 * §8 Typography: Comfortaa on the heading roles (display, headline, title),
 * EB Garamond on the body roles (body, label); all other style properties
 * stay at the Material 3 defaults.
 */
private fun hermesTypography(): Typography {
    val defaults = Typography()
    return defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = Comfortaa),
        displayMedium = defaults.displayMedium.copy(fontFamily = Comfortaa),
        displaySmall = defaults.displaySmall.copy(fontFamily = Comfortaa),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = Comfortaa),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = Comfortaa),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = Comfortaa),
        titleLarge = defaults.titleLarge.copy(fontFamily = Comfortaa),
        titleMedium = defaults.titleMedium.copy(fontFamily = Comfortaa),
        titleSmall = defaults.titleSmall.copy(fontFamily = Comfortaa),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = EBGaramond),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = EBGaramond),
        bodySmall = defaults.bodySmall.copy(fontFamily = EBGaramond),
        labelLarge = defaults.labelLarge.copy(fontFamily = EBGaramond),
        labelMedium = defaults.labelMedium.copy(fontFamily = EBGaramond),
        labelSmall = defaults.labelSmall.copy(fontFamily = EBGaramond),
    )
}

/**
 * Application theme. There is no theme selector (SPECIFICATION.md §2): the
 * app always follows the system dark/light setting, with dynamic color where
 * the platform offers it.
 */
@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = hermesTypography(),
        content = content,
    )
}
