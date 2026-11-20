package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SyncState

/**
 * A source whose server keeps the user's state for its own entries.
 *
 * @since tachiyomix 1.7
 */
interface SyncableSource : Source {

    /**
     * The user's state for [manga] on the server.
     */
    suspend fun getSyncState(manga: SManga): SyncState

    /**
     * Sets [chapters] of [manga] as read, or unread, on the server. Setting a state the server already has is
     * harmless. Throws if the server didn't take it.
     */
    suspend fun setChaptersRead(manga: SManga, chapters: List<SChapter>, read: Boolean)

    /**
     * Sets [manga] as a favorite, or not, on the server. Same rules as [setChaptersRead]. Unsupported when
     * [SyncState.favorite] is null.
     */
    suspend fun setFavorite(manga: SManga, favorite: Boolean)
}
