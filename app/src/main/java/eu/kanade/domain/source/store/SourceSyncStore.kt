package eu.kanade.domain.source.store

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Sync state kept for syncable sources: the latest change per chapter or entry that hasn't reached the server yet,
 * and which entries the server keeps favorites for.
 */
@Inject
@SingleIn(AppScope::class)
class SourceSyncStore(context: Context) {

    private val preferences by lazy { context.getSharedPreferences("source_sync", Context.MODE_PRIVATE) }

    @Synchronized
    fun set(kind: Kind, ids: List<Long>, value: Boolean) {
        preferences.edit { ids.forEach { putBoolean(kind.key(it), value) } }
    }

    /**
     * Removes the ids that still hold [value], leaving any set to the other value since.
     */
    @Synchronized
    fun remove(kind: Kind, ids: List<Long>, value: Boolean) {
        preferences.edit { ids.filter { get(kind, it) == value }.forEach { remove(kind.key(it)) } }
    }

    fun get(kind: Kind, id: Long): Boolean? =
        kind.key(id).takeIf(preferences::contains)?.let { preferences.getBoolean(it, false) }

    fun getAll(kind: Kind): Map<Long, Boolean> = preferences.all.mapNotNull { (key, value) ->
        key.removePrefix(kind.prefix).takeIf { key.startsWith(kind.prefix) }?.toLongOrNull()?.let {
            it to
                (value as Boolean)
        }
    }.toMap()

    enum class Kind(val prefix: String) {
        /** A chapter's read state to send. */
        READ("read_"),

        /** An entry's favorite state to send. */
        FAVORITE("favorite_"),

        /** An entry whose server keeps favorites. */
        HAS_FAVORITE("has_favorite_"),
        ;

        fun key(id: Long) = prefix + id
    }
}
