package eu.kanade.domain.source.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourceSyncWorker
import eu.kanade.domain.source.store.SourceSyncStore
import eu.kanade.domain.source.store.SourceSyncStore.Kind
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SyncableSource
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/**
 * Keeps the user's read chapters and favorites in step with syncable sources.
 */
@Inject
class SyncWithSource(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val store: SourceSyncStore,
) {

    /**
     * Queues [chapters] to be marked read or unread on their source's server, once the device is online.
     */
    suspend fun pushRead(chapters: List<Chapter>, read: Boolean) {
        val chapterIds = chapters.groupBy { it.mangaId }
            .filterKeys { getSource(it) != null }
            .values.flatten().map { it.id }
        if (chapterIds.isEmpty()) return
        store.set(Kind.READ, chapterIds, read)
        SourceSyncWorker.setupTask(context)
    }

    /**
     * Queues the entry [mangaId] to be set as a favorite, or not, on its source's server, if the server keeps favorites.
     */
    suspend fun pushFavorite(mangaId: Long, favorite: Boolean) {
        if (store.get(Kind.HAS_FAVORITE, mangaId) != true || getSource(mangaId) == null) return
        store.set(Kind.FAVORITE, listOf(mangaId), favorite)
        SourceSyncWorker.setupTask(context)
    }

    /**
     * Marks the chapters of [manga] that are read on the source's server as read, and returns their urls. Chapters
     * read only here stay read, and chapters with a change on its way to the server are left alone.
     */
    suspend fun pull(manga: Manga, source: Source): Set<String> {
        val syncableSource = source.asEnabled() ?: return emptySet()
        val state = try {
            syncableSource.getSyncState(manga.toSManga())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Failed to get the sync state of ${manga.title}" }
            return emptySet()
        }
        if (state.favorite != null) {
            store.set(Kind.HAS_FAVORITE, listOf(manga.id), true)
        } else {
            store.remove(Kind.HAS_FAVORITE, listOf(manga.id), true)
        }
        val chapters = chapterRepository.getChapterByMangaId(manga.id)
            .filter { !it.read && it.url in state.readChapterUrls && store.get(Kind.READ, it.id) == null }
        chapterRepository.updateAll(chapters.map { ChapterUpdate(it.id) { read = true } })
        return chapters.mapTo(HashSet()) { it.url }
    }

    /**
     * Sends the queued changes. Returns whether none are left.
     */
    suspend fun sendQueued(): Boolean {
        store.getAll(Kind.READ).entries
            .groupBy({ it.value }, { it.key })
            .forEach { (read, chapterIds) ->
                val chapters = chapterIds.mapNotNull { chapterRepository.getChapterById(it) }
                store.remove(Kind.READ, chapterIds - chapters.map { it.id }.toSet(), read)
                chapters.groupBy { it.mangaId }.forEach { (mangaId, mangaChapters) ->
                    send(Kind.READ, mangaId, mangaChapters.map { it.id }, read) { source, manga ->
                        source.setChaptersRead(manga, mangaChapters.map { it.toSChapter() }, read)
                    }
                }
            }
        store.getAll(Kind.FAVORITE).forEach { (mangaId, favorite) ->
            send(Kind.FAVORITE, mangaId, listOf(mangaId), favorite) { source, manga ->
                source.setFavorite(manga, favorite)
            }
        }
        return store.getAll(Kind.READ).isEmpty() && store.getAll(Kind.FAVORITE).isEmpty()
    }

    private suspend fun send(
        kind: Kind,
        mangaId: Long,
        ids: List<Long>,
        value: Boolean,
        action: suspend (SyncableSource, SManga) -> Unit,
    ) {
        // An entry can be deleted while its favorite change waits, e.g. when clearing the database
        val manga = runCatching { mangaRepository.getMangaById(mangaId) }.getOrNull()
        if (manga == null) {
            store.remove(kind, ids, value)
            return
        }
        // Kept while the extension is missing, as trackers keep theirs
        val source = sourceManager.get(manga.source) ?: return
        val syncableSource = source.asEnabled()
        if (syncableSource == null) {
            store.remove(kind, ids, value)
            return
        }
        try {
            action(syncableSource, manga.toSManga())
            store.remove(kind, ids, value)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Failed to sync ${manga.title}" }
        }
    }

    private suspend fun getSource(mangaId: Long): SyncableSource? =
        sourceManager.get(mangaRepository.getMangaById(mangaId).source)?.asEnabled()

    private fun Source.asEnabled(): SyncableSource? =
        (this as? SyncableSource)?.takeIf { sourcePreferences.syncWithSource(id).get() }
}
