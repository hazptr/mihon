package eu.kanade.tachiyomi

import android.content.Context
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Used by extensions.
 */
@Suppress("UNUSED")
object AppInfo {
    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionName(): String = BuildConfig.VERSION_NAME

    /**
     * A list of supported image MIME types by the reader.
     * e.g. ["image/jpeg", "image/png", ...]
     *
     * @since extension-lib 1.5
     */
    fun getSupportedImageMimeTypes(): List<String> = ImageUtil.ImageType.entries.map { it.mime }

    /**
     * Genre names the user hides app wide, as typed. Sources that can exclude genres
     * leave titles with a matching site genre out of browse and search.
     * A search filter that includes one of them wins.
     *
     * @since tachiyomix 1.7
     */
    fun getHiddenGenres(): Set<String> = sourcePreferences.hiddenGenres.get()

    private val sourcePreferences get() = Injekt.get<Context>().appGraph.sourcePreferences
}
