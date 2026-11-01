package app.jarvis.tv

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An app as a tile: its wide TV banner when it has one, else its icon. */
class AppTile(val app: TvApp, val banner: ImageBitmap?, val icon: ImageBitmap?)

/**
 * The apps first in line, in this order, until the list of apps for this TV is settled. Packages
 * not installed are skipped.
 */
val FAVOURITES =
    listOf(
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "in.startv.hotstar.dplus.tv",
        "com.disney.disneyplus",
        "com.spotify.tv.android",
        "com.apple.atve.androidtv.appletv",
        "com.tm.unifitv.tv",
        "com.vuclip.viu.tv",
        "com.plexapp.android",
        "com.google.android.youtube.tvkids",
    )

/** The apps the TV's own home screen lists, favourites first, then the rest by name. */
fun installedApps(pm: PackageManager, self: String): List<AppTile> {
  val found =
      pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
          .map { it.activityInfo }
          .filter { it.packageName != self }
          .distinctBy { it.packageName }
  val tiles =
      found.map { a ->
        // Banners are 320 × 180 dp; a tile is about half that, so half the pixels is plenty.
        val banner = (a.loadBanner(pm) ?: a.applicationInfo.loadBanner(pm))?.toBitmap(320, 180)?.asImageBitmap()
        val icon = if (banner == null) a.loadIcon(pm)?.toBitmap(96, 96)?.asImageBitmap() else null
        AppTile(TvApp(a.packageName, a.loadLabel(pm).toString()), banner, icon)
      }
  val rank = FAVOURITES.withIndex().associate { it.value to it.index }
  return tiles.sortedWith(compareBy({ rank[it.app.pkg] ?: Int.MAX_VALUE }, { it.app.label.lowercase() }))
}

/** What starts [pkg] on a TV, or on a phone-style app as a fallback. */
fun launchIntent(pm: PackageManager, pkg: String): Intent? =
    (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
