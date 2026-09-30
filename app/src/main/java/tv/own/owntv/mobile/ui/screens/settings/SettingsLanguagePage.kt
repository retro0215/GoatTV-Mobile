package tv.own.owntv.mobile.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.core.i18n.LocaleStore
import tv.own.owntv.core.i18n.SupportedLocale
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.MobileTextField
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.util.Locale

/**
 * Every language the app is packaged in, each in its own name, with how much of it is translated.
 *
 * A search box rather than the television's two-column wall: twenty-six languages do not fit on a
 * phone, and typing "por" is faster than scrolling to Portuguese. The coverage figure is shown
 * because an eighty-percent language still has English in it, and a user who knows that in advance
 * is not surprised by it afterwards.
 */
@Composable
fun SettingsLanguagePage(
    modifier: Modifier = Modifier,
    localeStore: LocaleStore = koinInject(),
) {
    val current = localeStore.currentTag.pref("")
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    val rows = remember { SupportedLocales.pickerRows.sortedBy { it.englishName.lowercase(Locale.ROOT) } }
    val needle = query.trim()
    val matches = remember(needle) { rows.filter { it.matches(needle) } }
    val systemLabel = stringResource(R.string.settings_language_system_default)
    val systemDescription = stringResource(R.string.settings_language_system_default_description)
    val showSystem = needle.isEmpty() ||
        systemLabel.contains(needle, true) || systemDescription.contains(needle, true)

    fun choose(tag: String) = scope.launch { runCatching { localeStore.set(tag) } }

    SettingsPage(modifier) {
        item(key = "search") {
            MobileTextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.settings_language_search_hint),
                modifier = Modifier.padding(
                    horizontal = MobileDimens.ListRowPaddingH,
                    vertical = MobileDimens.GapSmall,
                ),
            )
        }

        settingsGroup(key = "languages") {
            if (showSystem) {
                LanguageRow(
                    title = systemLabel,
                    subtitle = systemDescription,
                    coverage = null,
                    selected = current.isEmpty(),
                    onClick = { choose(SupportedLocales.SYSTEM_DEFAULT_TAG) },
                )
            }
            matches.forEach { locale ->
                LanguageRow(
                    title = locale.endonym,
                    subtitle = locale.englishName,
                    coverage = SupportedLocales.coverageBadgePercent(locale),
                    selected = current == locale.languageTag,
                    onClick = { choose(locale.languageTag) },
                )
            }
            if (!showSystem && matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_settings_match, needle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(MobileDimens.ListRowPaddingH),
                )
            }
        }
    }
}

/** Endonym, English name or tag — whichever the user happens to think of the language by. */
private fun SupportedLocale.matches(needle: String): Boolean =
    needle.isEmpty() ||
        endonym.contains(needle, true) ||
        englishName.contains(needle, true) ||
        languageTag.contains(needle, true)

@Composable
private fun LanguageRow(
    title: String,
    subtitle: String,
    coverage: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MobileListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        selected = selected,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (coverage != null) {
                    Text(
                        text = stringResource(R.string.settings_language_coverage, coverage),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = MobileDimens.GapSmall, vertical = 2.dp),
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = MobileIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
