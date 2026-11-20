package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.source.service.SourceSyncWorker
import eu.kanade.domain.source.store.SourceSyncStore
import eu.kanade.domain.source.store.SourceSyncStore.Kind
import eu.kanade.tachiyomi.source.SyncableSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class SyncWithSourceTest {

    private val source = mockk<SyncableSource>(relaxed = true) { every { id } returns SOURCE_ID }
    private val store = mockk<SourceSyncStore>(relaxed = true)
    private val syncWithSource = SyncWithSource(
        context = mockk(relaxed = true),
        sourceManager = mockk<SourceManager> { coEvery { get(SOURCE_ID) } returns source },
        sourcePreferences = mockk<SourcePreferences> {
            every { syncWithSource(SOURCE_ID) } returns mockk { every { get() } returns true }
        },
        mangaRepository = mockk<MangaRepository> {
            coEvery { getMangaById(MANGA_ID) } returns Manga.create().copy(id = MANGA_ID, source = SOURCE_ID)
        },
        chapterRepository = mockk(relaxed = true),
        store = store,
    )

    @BeforeEach
    fun setUp() {
        mockkObject(SourceSyncWorker)
        every { SourceSyncWorker.setupTask(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SourceSyncWorker)
    }

    @Test
    fun `queues a favorite change when the server keeps favorites`() = runBlocking {
        every { store.get(Kind.HAS_FAVORITE, MANGA_ID) } returns true

        syncWithSource.pushFavorite(MANGA_ID, favorite = false)

        verify { store.set(Kind.FAVORITE, listOf(MANGA_ID), false) }
        verify { SourceSyncWorker.setupTask(any()) }
    }

    @Test
    fun `queues nothing when the server has no favorites`() = runBlocking {
        every { store.get(Kind.HAS_FAVORITE, MANGA_ID) } returns null

        syncWithSource.pushFavorite(MANGA_ID, favorite = true)

        verify(exactly = 0) { store.set(Kind.FAVORITE, any(), any()) }
        verify(exactly = 0) { SourceSyncWorker.setupTask(any()) }
    }

    @Test
    fun `sends a queued favorite change to the source`() = runBlocking {
        every { store.getAll(Kind.READ) } returns emptyMap()
        every { store.getAll(Kind.FAVORITE) } returnsMany listOf(mapOf(MANGA_ID to true), emptyMap())

        assertTrue(syncWithSource.sendQueued())

        coVerify { source.setFavorite(any(), true) }
        verify { store.remove(Kind.FAVORITE, listOf(MANGA_ID), true) }
    }

    private companion object {
        const val SOURCE_ID = 10L
        const val MANGA_ID = 1L
    }
}
