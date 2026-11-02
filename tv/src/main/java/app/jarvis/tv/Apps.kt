package app.jarvis.tv

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.drawable.toBitmap

/** An app on Home: what starts it, its name, and its icon drawn to match the others. */
class HomeApp(val pkg: String, val name: String, val icon: ImageVector)

/** Home's apps, in this order. Every other app is in the drawer. */
val HOME_APPS =
    listOf(
        HomeApp("com.google.android.youtube.tv", "YouTube", AppIcons.YouTube),
        HomeApp("com.tm.unifitv.tv", "Unifi TV", AppIcons.UnifiTv),
        HomeApp("com.netflix.ninja", "Netflix", AppIcons.Netflix),
        // The new Disney+. The old one, Disney+ Hotstar (in.startv.hotstar.dplus.tv), closes in November 2026.
        HomeApp("com.disney.disneyplus", "Disney+", AppIcons.DisneyPlus),
        HomeApp("com.apple.atve.androidtv.appletv", "Apple TV", AppIcons.AppleTv),
    )

/** Names for apps whose own is the same as another's: both Disney+ apps call themselves "Disney+". */
private val NAMES = mapOf("in.startv.hotstar.dplus.tv" to "Disney+ Hotstar")

/** An app the TV's own home screen lists, and its icon for the drawer. */
class AppTile(val app: TvApp, val icon: ImageBitmap?)

/** Every app the TV's own home screen lists, by name. */
fun installedApps(pm: PackageManager, self: String): List<AppTile> =
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        .map { it.activityInfo }
        .filter { it.packageName != self }
        .distinctBy { it.packageName }
        .map { a -> AppTile(TvApp(a.packageName, NAMES[a.packageName] ?: a.loadLabel(pm).toString()), a.loadIcon(pm)?.toBitmap(96, 96)?.asImageBitmap()) }
        .sortedBy { it.app.label.lowercase() }

/**
 * The apps "open …" looks through: Home's first, by the names Home gives them, so "Disney+" is
 * the new one; then the rest.
 */
fun askable(apps: List<AppTile>): List<TvApp> {
  val installed = apps.map { it.app.pkg }.toSet()
  val home = HOME_APPS.filter { it.pkg in installed }.map { TvApp(it.pkg, it.name) }
  return home + apps.map { it.app }.filter { a -> home.none { it.pkg == a.pkg } }
}

/** What starts [pkg] on a TV, or on a phone-style app as a fallback. */
fun launchIntent(pm: PackageManager, pkg: String): Intent? =
    (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
