package eu.kanade.tachiyomi.source.model

/**
 * The user's state for an entry on a [eu.kanade.tachiyomi.source.SyncableSource]'s server.
 *
 * @since tachiyomix 1.7
 */
class SyncState(
    /**
     * Urls of the chapters read on the server.
     */
    val readChapterUrls: Set<String>,
    /**
     * Whether the entry is a favorite on the server, or null if the server has no favorites.
     */
    val favorite: Boolean?,
)
