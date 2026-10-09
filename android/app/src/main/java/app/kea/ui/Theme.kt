package app.kea.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.kea.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The KEA palette.
 *
 * Built on the same deep-teal identity as the launcher icon, so the app and the
 * icon read as one thing in the launcher.
 */
private val Teal = Color(0xFF5EEAD4)
private val DeepTeal = Color(0xFF0B1412)
private val MidTeal = Color(0xFF123A36)
private val Amber = Color(0xFFFBBF24)

private val DarkScheme = darkColorScheme(
    primary = Teal,
    onPrimary = DeepTeal,
    primaryContainer = MidTeal,
    onPrimaryContainer = Teal,
    secondary = Amber,
    onSecondary = DeepTeal,
    background = DeepTeal,
    onBackground = Color(0xFFE6F2EF),
    surface = Color(0xFF111C1A),
    onSurface = Color(0xFFE6F2EF),
    surfaceVariant = Color(0xFF1B2A27),
    onSurfaceVariant = Color(0xFFA8C0BA),
    outline = Color(0xFF3E5A54),
    error = Color(0xFFFCA5A5),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2A),
    secondary = Color(0xFFB45309),
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF0B1412),
    surface = Color.White,
    onSurface = Color(0xFF0B1412),
    surfaceVariant = Color(0xFFE2EFEB),
    onSurfaceVariant = Color(0xFF415A54),
    outline = Color(0xFFBFDBD3),
    error = Color(0xFFB91C1C),
)

private val KeaTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Typography().labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun KeaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = KeaTypography,
        content = content,
    )
}

// ---- formatting -----------------------------------------------------------
//
// Every one of these takes a Context so the *current* app locale is used. That
// is what makes "vor 2 Min." appear on a German phone; a Locale.US default
// would silently produce English on every device.

@Composable
fun rememberLocale(): Locale {
    val config: Configuration = LocalConfiguration.current
    val context: Context = LocalContext.current
    return remember(config) {
        val tags = context.resources.configuration.locales
        if (!tags.isEmpty) tags[0] else Locale.getDefault()
    }
}

/** "just now" / "3 min" / "2 h" / "5 d", in the app language. */
fun formatAge(context: Context, atMs: Long, now: Long = System.currentTimeMillis()): String {
    if (atMs <= 0L) return context.getString(R.string.member_unknown)
    val delta = (now - atMs).coerceAtLeast(0L)

    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)

    return when {
        minutes < 1 -> context.getString(R.string.duration_just_now)
        minutes < 60 -> context.getString(R.string.duration_minutes, minutes.toInt())
        hours < 24 -> context.getString(R.string.duration_hours, hours.toInt())
        else -> context.getString(R.string.duration_days, days.toInt())
    }
}

/** "420 m" / "1.4 km" — metric only, because the app ships EN and DE. */
fun formatDistance(context: Context, meters: Double): String = when {
    meters < 1000 -> context.getString(R.string.distance_meters, meters.toInt())
    else -> context.getString(R.string.distance_km, meters / 1000)
}

fun formatSpeed(context: Context, metersPerSecond: Double): String =
    context.getString(R.string.member_speed, String.format(Locale.getDefault(), "%.1f", metersPerSecond * 3.6))

fun formatClock(atMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(atMs))