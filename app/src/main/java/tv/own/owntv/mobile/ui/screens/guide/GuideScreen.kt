package tv.own.owntv.mobile.ui.screens.guide

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.SharedFlow
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.database.entity.RecordingRuleEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.serialize
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrowseCategorySheet
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.MobileTextField
import tv.own.owntv.mobile.ui.components.mobileGroupPlate
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.live.LiveCategory
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Redesigned Mobile TV Guide:
 * Compact top selected-program detail header, responsive control row (Date, Category, Refresh, Search),
 * vertical category browser, and a synchronized 30-minute timeline EPG.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    scrollToTop: SharedFlow<String>,
    onOpenChannel: (Long) -> Unit,
    onAddEpg: () -> Unit,
    modifier: Modifier = Modifier,
    vm: GuideViewModel = koinViewModel(),
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selected.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val guideDays by vm.guideDays.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val density by vm.densityPct.collectAsStateWithLifecycle()
    val sort by vm.sortGuide.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val summary by vm.matchSummary.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = "guide", scrollToTop = scrollToTop)

    var selectedChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var selectedProgramme by remember { mutableStateOf<EpgProgrammeEntity?>(null) }
    var selectedDescription by remember { mutableStateOf<String?>(null) }

    var showDateSheet by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showChannelSearch by remember { mutableStateOf(false) }
    var actionSheetFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var categoryMenuFor by remember { mutableStateOf<LiveCategory?>(null) }

    val nowMs by rememberClockTick()
    val times = rememberGuideTimeFormat()

    // Fetch program description when selected programme changes
    LaunchedEffect(selectedProgramme?.id) {
        selectedDescription = selectedProgramme?.id?.let { vm.description(it) }
    }

    // Auto-populate default selected programme from first channel if unselected
    LaunchedEffect(channels.itemCount, window.start) {
        if (selectedProgramme == null && channels.itemCount > 0) {
            val firstChannel = channels.peek(0)
            if (firstChannel != null) {
                selectedChannel = firstChannel
                val rows = vm.row(firstChannel)
                if (rows.isNotEmpty()) {
                    selectedProgramme = rows.firstOrNull { nowMs in it.startMs..it.stopMs } ?: rows.first()
                }
            }
        }
    }

    val selectedCategoryLabel = categories.firstOrNull { it.key == selectedCategory }?.label()
        ?: stringResource(R.string.content_category_all_channels)

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // --- 1. TOP SELECTED PROGRAM DETAIL HEADER ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .mobileGroupPlate()
                .padding(horizontal = MobileDimens.PagePaddingH, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapMedium),
            ) {
                ChannelLogoImage(
                    url = selectedChannel?.displayLogoUrl,
                    modifier = Modifier.size(36.dp),
                    fallback = {
                        Icon(
                            imageVector = MobileIcons.LiveTv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    },
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedProgramme?.title ?: stringResource(R.string.content_preview_select_channel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val prog = selectedProgramme
                    if (prog != null) {
                        val timeStr = stringResource(
                            R.string.content_epg_time_range,
                            times.format(Date(prog.startMs)),
                            times.format(Date(prog.stopMs)),
                        )
                        val isLiveNow = day == 0 && (nowMs in prog.startMs..prog.stopMs)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLiveNow) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFDC3232), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.live_badge_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Watch Live Button (if live program)
                val prog = selectedProgramme
                val ch = selectedChannel
                if (prog != null && ch != null && day == 0 && (nowMs in prog.startMs..prog.stopMs)) {
                    Button(
                        onClick = { onOpenChannel(ch.id) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(text = stringResource(R.string.guide_watch_live), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Description
            if (!selectedDescription.isNullOrBlank()) {
                Text(
                    text = selectedDescription!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // --- 2. CONTROL ROW: [ DATE ] [ CATEGORY ] [ REFRESH ] [ SEARCH ] ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MobileDimens.PagePaddingH, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Date Selector Button
            val dateLabel = if (day == 0) stringResource(R.string.guide_today) else dateLabelForOffset(day)
            OutlinedButton(
                onClick = { showDateSheet = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(MobileIcons.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = dateLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Category Selector Button
            FilledTonalButton(
                onClick = { showCategorySheet = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(MobileIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = selectedCategoryLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Refresh Button
            IconButton(onClick = { if (!matching) vm.autoMatchEpg() }) {
                Icon(MobileIcons.Refresh, contentDescription = stringResource(R.string.guide_refresh_button))
            }

            // Search Toggle Button
            IconButton(onClick = { showChannelSearch = !showChannelSearch }) {
                Icon(
                    imageVector = if (showChannelSearch) MobileIcons.Close else MobileIcons.Search,
                    contentDescription = stringResource(R.string.live_search_channels),
                )
            }
        }

        // Optional Channel Search Input Field
        if (showChannelSearch) {
            MobileTextField(
                value = query,
                onValueChange = vm::setQuery,
                label = stringResource(R.string.live_search_channels),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MobileDimens.PagePaddingH, vertical = 2.dp),
            )
        }

        if (matching) {
            MatchingBanner()
        } else {
            summary?.let { MatchSummaryBanner(it, onDismiss = vm::clearReview) }
        }

        if (stats?.mismatchedIds == true) {
            GuideNotice(stringResource(R.string.content_epg_mismatched_ids))
        }

        // --- 3. TIMELINE GRID AREA ---
        Box(modifier = Modifier.weight(1f)) {
            if (channels.itemCount == 0) {
                EmptyGuide(
                    query = query,
                    stats = stats,
                    filterHidesEverything = sort == SettingsRepository.GuideSort.FAVORITES ||
                        sort == SettingsRepository.GuideSort.CATCHUP,
                    onAddEpg = onAddEpg,
                )
            } else {
                GuideGrid(
                    vm = vm,
                    channels = channels,
                    listState = listState,
                    window = window,
                    densityPct = density,
                    isToday = (day == 0),
                    selectedProgramme = selectedProgramme,
                    onSelectProgramme = { channel, programme ->
                        selectedChannel = channel
                        selectedProgramme = programme
                    },
                    onOpenChannel = onOpenChannel,
                    onMenu = { menuFor = it },
                )
            }
        }
    }

    // --- SHEETS & DIALOGS ---
    if (showDateSheet) {
        DateSelectionSheet(
            guideDays = guideDays,
            selectedDay = day,
            onSelectDay = { offset ->
                vm.selectDay(offset)
                showDateSheet = false
            },
            onDismiss = { showDateSheet = false },
        )
    }

    if (showCategorySheet) {
        CategoryBrowserSheet(
            categories = categories,
            selectedKey = selectedCategory,
            onSelect = { key ->
                vm.select(key)
                showCategorySheet = false
            },
            onDismiss = { showCategorySheet = false },
        )
    }

    if (review.isNotEmpty()) {
        val includeLogos by vm.includeGuideLogos.collectAsStateWithLifecycle()
        EpgReviewSheet(
            suggestions = review,
            includeLogos = includeLogos,
            onIncludeLogos = vm::setIncludeGuideLogos,
            onAccept = vm::acceptSuggestion,
            onSkip = vm::skipSuggestion,
            onAcceptAll = vm::acceptAllSuggestions,
            onDone = vm::clearReview,
        )
    }

    menuFor?.let { channel ->
        GuideChannelSheet(channel = channel, vm = vm, onDismiss = { menuFor = null })
    }

    actionSheetFor?.let { (channel, programme) ->
        ProgrammeSheet(
            channel = channel,
            programme = programme,
            isFavorite = channel.id in favorites,
            vm = vm,
            onOpenChannel = onOpenChannel,
            onDismiss = { actionSheetFor = null },
        )
    }

    categoryMenuFor?.let { category ->
        BrowseCategorySheet(
            title = category.label(),
            onHide = { vm.hideCategory(category.key) },
            onMove = { kind -> vm.moveCategory(category.key, kind) },
            onDismiss = { categoryMenuFor = null },
        )
    }
}

/** Date Selection Sheet */
@Composable
private fun DateSelectionSheet(
    guideDays: Int,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val todayLabel = stringResource(R.string.guide_today)
    val labels = remember(locale, guideDays, todayLabel) {
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
        val format = SimpleDateFormat(pattern, locale)
        val cal = Calendar.getInstance()
        List(guideDays) { offset ->
            if (offset == 0) todayLabel
            else {
                val d = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
                format.format(d.time)
            }
        }
    }

    MobileBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.guide_select_date),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = MobileDimens.PagePaddingH, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(labels.size) { index ->
                    val isSelected = (index == selectedDay)
                    MobileListRow(
                        title = labels[index],
                        selected = isSelected,
                        trailing = if (isSelected) {
                            { Icon(MobileIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = { onSelectDay(index) },
                    )
                }
            }
        }
    }
}

/** Vertical Category Browser Bottom Sheet */
@Composable
private fun CategoryBrowserSheet(
    categories: List<LiveCategory>,
    selectedKey: LiveKey,
    onSelect: (LiveKey) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val categoryItems = categories.map { Pair(it, it.label()) }
    val filtered = remember(categoryItems, query) {
        if (query.isBlank()) categoryItems
        else categoryItems.filter { (_, label) ->
            label.contains(query.trim(), ignoreCase = true)
        }
    }

    MobileBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.live_categories_button),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = MobileDimens.PagePaddingH, vertical = 8.dp),
            )

            MobileTextField(
                value = query,
                onValueChange = { query = it },
                label = stringResource(R.string.live_search_categories),
                modifier = Modifier.padding(horizontal = MobileDimens.PagePaddingH, vertical = 4.dp),
            )

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(filtered.size, key = { index -> filtered[index].first.key.serialize() }) { index ->
                    val (cat, label) = filtered[index]
                    val isSelected = (cat.key == selectedKey)
                    MobileListRow(
                        title = label,
                        selected = isSelected,
                        trailing = if (isSelected) {
                            { Icon(MobileIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = {
                            onSelect(cat.key)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun dateLabelForOffset(offset: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
    val format = remember(locale, pattern) { SimpleDateFormat(pattern, locale) }
    val cal = remember(offset) {
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
    }
    return format.format(cal.time)
}

/** One programme, opened. */
@Composable
private fun ProgrammeSheet(
    channel: ChannelEntity,
    programme: EpgProgrammeEntity,
    isFavorite: Boolean,
    vm: GuideViewModel,
    onOpenChannel: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var description by remember(programme.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(programme.id) { description = vm.description(programme.id) }

    val times = rememberGuideTimeFormat()

    MobileBottomSheet(onDismissRequest = onDismiss, title = programme.title) {
        Text(
            text = stringResource(
                R.string.content_epg_time_range,
                times.format(Date(programme.startMs)),
                times.format(Date(programme.stopMs)),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPaddingH),
        )
        description?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = MobileDimens.ScreenPaddingH,
                    vertical = MobileDimens.GapSmall,
                ),
            )
        }
        HorizontalDivider()
        MobileListRow(
            title = stringResource(R.string.content_epg_watch_channel),
            onClick = { onDismiss(); onOpenChannel(channel.id) },
        )
        if (vm.canCatchup(channel, programme)) {
            MobileListRow(
                title = stringResource(R.string.content_epg_watch_start),
                onClick = { onDismiss(); vm.playCatchup(channel, programme); onOpenChannel(channel.id) },
            )
        }
        if (vm.canRecord(channel, programme)) {
            val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
            val existing = remember(recordingRows, channel.id, programme.startMs) {
                vm.recordingFor(channel, programme)
            }
            val clash by produceState<String?>(null, channel.id, programme.startMs, recordingRows) {
                value = vm.clashFor(channel, programme)
            }
            when (existing?.status) {
                RecordingStatus.RECORDING -> MobileListRow(
                    title = stringResource(R.string.recording_stop),
                    leading = { Icon(MobileIcons.Pause, contentDescription = null) },
                    onClick = { vm.stopRecording(existing); onDismiss() },
                )
                RecordingStatus.SCHEDULED -> MobileListRow(
                    title = stringResource(R.string.common_cancel),
                    leading = { Icon(MobileIcons.Delete, contentDescription = null) },
                    onClick = { vm.cancelRecording(existing); onDismiss() },
                )
                else -> MobileListRow(
                    title = stringResource(
                        if (programme.stopMs <= System.currentTimeMillis()) {
                            R.string.recording_from_archive
                        } else {
                            R.string.recording_record
                        },
                    ),
                    subtitle = clash?.let { stringResource(R.string.recording_clash_with, it) },
                    subtitleMaxLines = 2,
                    leading = { Icon(MobileIcons.LiveTv, contentDescription = null) },
                    onClick = { vm.record(channel, programme); onDismiss() },
                )
            }
            if (programme.stopMs > System.currentTimeMillis()) {
                val seriesRule by produceState<RecordingRuleEntity?>(
                    null, channel.id, programme.title, recordingRows,
                ) {
                    value = vm.seriesRuleFor(channel, programme)
                }
                MobileListRow(
                    title = stringResource(
                        if (seriesRule != null) R.string.recording_stop_series
                        else R.string.recording_record_series,
                    ),
                    leading = { Icon(MobileIcons.CalendarMonth, contentDescription = null) },
                    onClick = {
                        seriesRule?.let { vm.stopSeries(it) } ?: vm.recordSeries(channel, programme)
                        onDismiss()
                    },
                )
            }
        }
        MobileListRow(
            title = stringResource(
                if (isFavorite) R.string.content_epg_unfavourite else R.string.content_epg_favourite,
            ),
            leading = {
                Icon(
                    imageVector = if (isFavorite) MobileIcons.Star else MobileIcons.StarBorder,
                    contentDescription = null,
                )
            },
            onClick = { vm.toggleFavorite(channel); onDismiss() },
        )
    }
}

/** Nothing to draw */
@Composable
private fun EmptyGuide(
    query: String,
    stats: GuideStats?,
    filterHidesEverything: Boolean,
    onAddEpg: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(MobileDimens.GapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val noEpg = stats?.hasEpgSources == false
        val hasGuide = (stats?.programmes ?: 0) > 0
        val filtered = filterHidesEverything && hasGuide
        Text(
            text = when {
                query.isNotBlank() -> stringResource(R.string.content_epg_no_channels_query, query)
                filtered -> stringResource(R.string.content_epg_filter_hides_all)
                noEpg -> stringResource(R.string.content_epg_empty)
                else -> stringResource(R.string.content_epg_add_playlist)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (query.isBlank() && noEpg && !filtered) {
            Text(
                text = stringResource(R.string.content_epg_add_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = MobileDimens.GapSmall),
            )
            MobileButton(text = stringResource(R.string.content_epg_add), onClick = onAddEpg)
        }
    }
}

@Composable
private fun MatchingBanner() {
    Column(Modifier.fillMaxWidth()) {
        GuideNotice(stringResource(R.string.content_epg_matching))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MobileDimens.ScreenPaddingH),
        )
    }
}

@Composable
private fun MatchSummaryBanner(summary: EpgMatchSummary, onDismiss: () -> Unit) {
    MobileListRow(
        title = epgMatchSummaryText(summary),
        trailing = {
            Text(
                text = stringResource(R.string.common_done),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onDismiss,
    )
}

@Composable
private fun GuideNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = MobileDimens.ScreenPaddingH,
            vertical = MobileDimens.GapTiny,
        ),
    )
}

@Composable
private fun LiveCategory.label(): String = when (builtIn) {
    LiveCategory.BuiltIn.ALL -> stringResource(R.string.content_epg_all_categories)
    LiveCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    else -> title.orEmpty()
}

@Composable
internal fun rememberGuideTimeFormat(): java.text.DateFormat {
    val locales = LocalConfiguration.current.locales
    return remember(locales) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
}

@Composable
internal fun ChannelLogo(channel: ChannelEntity) {
    ChannelLogoImage(
        url = channel.displayLogoUrl,
        modifier = Modifier.size(32.dp),
        fallback = {
            Icon(
                imageVector = MobileIcons.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        },
    )
}
