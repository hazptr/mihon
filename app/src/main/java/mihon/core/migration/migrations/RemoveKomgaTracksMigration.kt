package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.track.repository.TrackRepository

/**
 * Removes the Komga tracker's tracks and login. The Komga source syncs read state itself.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class RemoveKomgaTracksMigration(
    private val trackRepository: TrackRepository,
    private val preferenceStore: PreferenceStore,
) : Migration {
    override val version: Float = 40f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        trackRepository.getTracks()
            .filter { it.trackerId == KOMGA_TRACKER_ID }
            .forEach { trackRepository.delete(it.mangaId, it.trackerId) }
        listOf("pref_mangasync_username_", "pref_mangasync_password_", "pref_mangasync_displayname_")
            .forEach { preferenceStore.getString(Preference.privateKey("$it$KOMGA_TRACKER_ID")).delete() }
        return true
    }

    private companion object {
        const val KOMGA_TRACKER_ID = 6L
    }
}
