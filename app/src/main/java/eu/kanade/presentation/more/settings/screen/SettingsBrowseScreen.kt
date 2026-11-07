package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.app.di.appGraph
import mihon.domain.extension.model.ContentWarning
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.Preference as PreferenceData

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        val getExtensionStoreCountAsFlow = remember { context.appGraph.getExtensionStoreCountAsFlow }

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)
        val hiddenGenresPref = remember { CommaSeparatedPreference(sourcePreferences.hiddenGenres) }
        val hiddenGenres by hiddenGenresPref.collectAsState()
        val sourceManager = remember { context.appGraph.sourceManager }
        val hidingSources by produceState(initialValue = "") {
            value = sourceManager.getOnlineSources()
                .filter { it.supportsHiddenGenres }
                .map { it.name }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .joinToString()
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        subtitle = pluralStringResource(MR.plurals.num_repos, reposCount.toInt(), reposCount),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_extensions),
                preferenceItems = listOf(
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = sourcePreferences.enabledContentWarnings,
                        entries = mapOf(
                            ContentWarning.SAFE to stringResource(MR.strings.ext_content_warning_safe),
                            ContentWarning.MIXED to stringResource(MR.strings.ext_content_warning_mixed),
                            ContentWarning.NSFW to stringResource(MR.strings.ext_content_warning_nsfw),
                        ),
                        title = stringResource(MR.strings.pref_allowed_content_warnings),
                        subtitleProvider = { value, entries ->
                            remember(value, entries) {
                                entries.filterKeys { it in value }.values.joinToString()
                            }
                                .takeUnless { it.isBlank() }
                                ?: stringResource(MR.strings.none)
                        },
                        onValueChanged = { newValue ->
                            val added = newValue - sourcePreferences.enabledContentWarnings.get()
                            if (added.any { it != ContentWarning.SAFE }) {
                                (context as FragmentActivity).authenticate(
                                    title = context.stringResource(MR.strings.pref_allowed_content_warnings),
                                )
                            } else {
                                true
                            }
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.applyContentWarningsToInstalled,
                        title = stringResource(MR.strings.pref_apply_content_warnings_to_installed),
                        subtitle = stringResource(MR.strings.pref_apply_content_warnings_to_installed_summary),
                        onValueChanged = { newValue ->
                            if (newValue) {
                                true
                            } else {
                                (context as FragmentActivity).authenticate(
                                    title = context.stringResource(
                                        MR.strings.pref_apply_content_warnings_to_installed,
                                    ),
                                )
                            }
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.content_warnings_info)),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_hidden_genres),
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = hiddenGenresPref,
                        title = stringResource(MR.strings.pref_hidden_genres),
                        subtitle = if (hiddenGenres.isBlank()) {
                            stringResource(MR.strings.pref_hidden_genres_summary)
                        } else {
                            "%s"
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.hidden_genres_info)),
                    Preference.PreferenceItem.InfoPreference(
                        if (hidingSources.isEmpty()) {
                            stringResource(MR.strings.hidden_genres_no_sources)
                        } else {
                            stringResource(MR.strings.hidden_genres_sources, hidingSources)
                        },
                    ),
                ),
            ),
        )
    }
}

/**
 * Edits a string set as one comma separated string.
 */
private class CommaSeparatedPreference(
    private val preference: PreferenceData<Set<String>>,
) : PreferenceData<String> {

    override fun key() = preference.key()

    override fun get() = join(preference.get())

    override fun set(value: String) {
        preference.set(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    override fun isSet() = preference.isSet()

    override fun delete() = preference.delete()

    override fun defaultValue() = join(preference.defaultValue())

    override fun changes() = preference.changes().map(::join)

    override fun stateIn(scope: CoroutineScope): StateFlow<String> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())

    private fun join(value: Set<String>) = value.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString()
}
