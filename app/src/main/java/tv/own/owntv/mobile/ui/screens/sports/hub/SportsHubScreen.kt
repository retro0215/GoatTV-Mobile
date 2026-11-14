package tv.own.owntv.mobile.ui.screens.sports.hub

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.mobile.ui.components.MobileButtonStyle
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.player.VideoStage
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.live.addToMultiview
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewContent
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewModel
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewPreferences
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewSession
import tv.own.owntv.mobile.ui.screens.multiview.PerformanceWarning
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.player.describe
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.sports.live.SportsAccessIssue
import tv.own.owntv.mobile.sports.live.SportsChannelOffer
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsEventStatus
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.sports.live.SportsTeam
import tv.own.owntv.mobile.sports.live.SportsWatchCheck
import tv.own.owntv.mobile.sports.live.SportsWatchRejection
import tv.own.owntv.mobile.sports.live.VisibleSportsChannel
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.FilterChipRow
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.sports.SportsScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Saves the hub's selection as a plain list (tab name, sport, epoch day, league id). */
private val SelectionSaver = listSaver<SportsHubSelection, Any?>(
    save = { it.toSaved() },
    restore = { SportsHubSelection.fromSaved(it) },
)

/**
 * The Sports tab: a hub with Live & Today, Schedule and Leagues — games from the GoatTV Sports
 * service, with scores and the number of channels this profile can use for each, for All Sports or one
 * sport — and Channels, the Sports screen the app already had, hosted here unchanged (its preview,
 * playback, Multiview and lifecycle are its own).
 *
 * The tab, the sport, the Schedule's day and the open league are one saved selection, shared by the
 * tabs and kept across rotation. A card opens a details panel whose Where to Watch list can Watch a
 * channel full screen, Preview it inside the panel, or Add it to Multiview; Back from the player or the
 * grid returns to the panel.
 */
@Composable
fun SportsHubScreen(
    scrollToTop: SharedFlow<String>,
    onOpenPlayer: () -> Unit,
    onOpenMultiview: (openPicker: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vm: SportsHubViewModel = koinViewModel(),
) {
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(SportsHubSelection()) }

    // One poller for the whole hub: active while the screen is at least STARTED and a schedule tab is
    // showing — the Channels tab, leaving Sports or the app going to the background stops it and cancels
    // any request in flight. The store allows one fetch at a time across every caller.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTab by rememberUpdatedState(selection.tab)
    LaunchedEffect(lifecycle, vm) {
        vm.runPolling(
            combine(lifecycle.currentStateFlow, snapshotFlow { currentTab }) { state, tab ->
                sportsPollingActive(state.isAtLeast(Lifecycle.State.STARTED), tab)
            },
        )
    }

    val sports by vm.sports.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val sport = SportsSportFilter.effective(selection.sport, sports)
    val zone = ZoneId.systemDefault()
    val todayEpoch = LocalDate.now(zone).toEpochDay()
    val days = remember(todayEpoch, zone) { SportsScheduleDays.days(System.currentTimeMillis(), zone) }
    val day = SportsScheduleDays.effective(selection.epochDay, days)
    val leagueId = SportsLeaguesModel.effective(selection.leagueId, leagues, sport)
    // A restored league that no longer fits is forgotten, not merely hidden, so it cannot reappear.
    val normalized = selection.normalized(leagues, sport)
    if (normalized != selection) SideEffect { selection = normalized }
    val query = SportsHubQuery(selection.tab, sport, day, leagueId)
    SideEffect { vm.show(query) }

    val tabLabels = listOf(
        stringResource(R.string.sports_tab_live_today),
        stringResource(R.string.sports_tab_schedule),
        stringResource(R.string.sports_tab_leagues),
        stringResource(R.string.sports_tab_channels),
    )
    Column(modifier.fillMaxSize()) {
        FilterChipRow(
            labels = tabLabels,
            selectedIndex = selection.tab.ordinal,
            onSelect = { index -> SportsHubTab.entries.getOrNull(index)?.let { selection = selection.copy(tab = it) } },
        )
        if (selection.tab != SportsHubTab.CHANNELS && sports.isNotEmpty()) {
            SportSelector(sports, sport, onSelect = { selection = selection.withSport(it) })
        }
        val pane = Modifier.weight(1f).fillMaxWidth()
        val nav = SportsHubNav(openPlayer = onOpenPlayer, openMultiview = { onOpenMultiview(false) })
        when (selection.tab) {
            SportsHubTab.LIVE_TODAY -> LiveTodayPane(vm, scrollToTop, nav, pane)
            SportsHubTab.SCHEDULE -> SchedulePane(vm, days, day, onSelectDay = { selection = selection.copy(epochDay = it.toEpochDay()) }, scrollToTop, nav, pane)
            SportsHubTab.LEAGUES -> LeaguesPane(
                vm = vm,
                leagues = leagues,
                sport = sport,
                openLeagueId = leagueId,
                onOpenLeague = { selection = selection.copy(leagueId = it) },
                scrollToTop = scrollToTop,
                nav = nav,
                modifier = pane,
            )
            SportsHubTab.CHANNELS -> SportsScreen(
                scrollToTop = scrollToTop,
                onOpenPlayer = onOpenPlayer,
                onOpenMultiview = onOpenMultiview,
                modifier = pane,
            )
        }
    }
}

/** All Sports, then each sport the backend has leagues for. */
@Composable
private fun SportSelector(sports: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    val labels = listOf(stringResource(R.string.sports_all_sports)) + sports.map { sportName(it) }
    FilterChipRow(
        labels = labels,
        selectedIndex = selected?.let { sports.indexOf(it) + 1 } ?: 0,
        onSelect = { index -> onSelect(if (index == 0) null else sports.getOrNull(index - 1)) },
    )
}

@Composable
private fun sportName(sport: String): String = when (sport) {
    "football" -> stringResource(R.string.sports_sport_football)
    "basketball" -> stringResource(R.string.sports_sport_basketball)
    "baseball" -> stringResource(R.string.sports_sport_baseball)
    "hockey" -> stringResource(R.string.sports_sport_hockey)
    "soccer" -> stringResource(R.string.sports_sport_soccer)
    "mma" -> stringResource(R.string.sports_sport_mma)
    "boxing" -> stringResource(R.string.sports_sport_boxing)
    else -> stringResource(R.string.sports_sport_other)
}

/** Cards per row: one on a phone held upright, more across in landscape or on a tablet. */
private fun cardsAcross(maxWidth: Dp): Int = ((maxWidth - MobileDimens.ScreenPaddingH * 2) / CARD_MIN_WIDTH).toInt().coerceIn(1, 3)

private val ListPadding = PaddingValues(start = MobileDimens.ScreenPaddingH, end = MobileDimens.ScreenPaddingH, bottom = MobileDimens.GapMedium)

/** What a game card needs besides the game: its league, this profile's channel count, and opening it. */
/**
 * The game whose details are open. Its id is saved, so the panel comes back after the player (Watch,
 * then Back) and after a rotation, showing the game's newest copy; the copy tapped is kept for as long
 * as the screen lives, for a game that has since left the list.
 */
private class OpenGame(private val id: MutableState<String?>) {
    private var tapped by mutableStateOf<SportsEvent?>(null)

    fun open(event: SportsEvent) {
        tapped = event
        id.value = event.id
    }

    fun close() {
        tapped = null
        id.value = null
    }

    fun current(shown: List<SportsEvent>): SportsEvent? {
        val open = id.value ?: return null
        return shown.firstOrNull { it.id == open } ?: tapped?.takeIf { it.id == open }
    }
}

@Composable
private fun rememberOpenGame(): OpenGame {
    val id = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(id) { OpenGame(id) }
}

/** Whether Live & Today's list is at its top, followed outside composition (not snapshot state). */
private class SportsListPosition {
    var atTop: Boolean = true
}

private class CardContext(
    val leagues: Map<String, SportsLeague>,
    val counts: SportsChannelCounts,
    val profileId: Long,
    val onOpen: (SportsEvent) -> Unit,
)

/** [games] as rows of [across] cards. [keyPrefix] keeps keys unique across sections. */
private fun LazyListScope.gameRows(games: List<SportsEvent>, across: Int, keyPrefix: String, ctx: CardContext, headingDay: LocalDate? = null) {
    items(games.chunked(across), key = { row -> "r:$keyPrefix:" + row.first().id }) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
            for (game in row) {
                GameCard(
                    event = game,
                    league = ctx.leagues[game.leagueId],
                    channelCount = SportsChannelCounts.shown(ctx.counts, game.id, ctx.profileId),
                    onOpen = { ctx.onOpen(game) },
                    headingDay = headingDay,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun LoadingBox(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val loading = stringResource(R.string.sports_loading)
        CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
    }
}

@Composable
private fun LiveTodayPane(vm: SportsHubViewModel, scrollToTop: SharedFlow<String>, nav: SportsHubNav, modifier: Modifier = Modifier) {
    val state by vm.today.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val byId = remember(leagues) { leagues.associateBy { it.id } }
    val details = rememberOpenGame()
    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)

    // Games that just went live are inserted above the list's first visible item; a list that was at
    // its top is held there (before the next layout) so they show. Whether it is at its top is followed
    // outside composition and kept in a plain holder, so scrolling never recomposes the pane — and the
    // check below, made while composing the new content, still sees where the list was before it.
    val reveal = remember { SportsLiveReveal() }
    val position = remember { SportsListPosition() }
    LaunchedEffect(listState) {
        snapshotFlow { SportsLiveReveal.atTop(listState.firstVisibleItemIndex) }.collect { position.atTop = it }
    }
    (state as? SportsTodayUiState.Content)?.let { content ->
        if (reveal.shouldPinTop(SportsLiveReveal.liveIds(content.sections), position.atTop)) listState.requestScrollToItem(0)
    }

    when (val s = state) {
        SportsTodayUiState.Loading -> LoadingBox(modifier)
        SportsTodayUiState.Unavailable -> Message(stringResource(R.string.sports_unavailable), modifier)
        is SportsTodayUiState.Empty -> Column(modifier) {
            if (s.stale) StaleNotice()
            Message(stringResource(if (s.filtered) R.string.sports_today_empty_sport else R.string.sports_today_empty), Modifier.weight(1f).fillMaxWidth())
        }
        is SportsTodayUiState.Content -> BoxWithConstraints(modifier) {
            val across = cardsAcross(maxWidth)
            val ctx = CardContext(s.leagues, counts, profileId) { details.open(it) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                if (s.stale) item(key = "stale") { StaleNotice() }
                for (section in s.sections) {
                    item(key = "h:${section.group}") { GroupHeader(section.group) }
                    gameRows(section.games, across, section.group.name, ctx)
                }
            }
        }
    }

    // The open panel follows the game: its clock and score update with each refresh.
    details.current((state as? SportsTodayUiState.Content)?.sections?.flatMap { it.games }.orEmpty())?.let { game ->
        GameDetailsSheet(game, byId[game.leagueId], vm, profileId, nav, onDismiss = details::close)
    }
}

@Composable
private fun SchedulePane(
    vm: SportsHubViewModel,
    days: List<LocalDate>,
    day: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    scrollToTop: SharedFlow<String>,
    nav: SportsHubNav,
    modifier: Modifier = Modifier,
) {
    val state by vm.schedule.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val byId = remember(leagues) { leagues.associateBy { it.id } }
    val details = rememberOpenGame()
    Column(modifier) {
        FilterChipRow(
            labels = days.map { dayChipLabel(it) },
            selectedIndex = days.indexOf(day),
            onSelect = { index -> days.getOrNull(index)?.let(onSelectDay) },
        )
        val body = Modifier.weight(1f).fillMaxWidth()
        when (val s = state) {
            SportsScheduleUiState.Loading -> LoadingBox(body)
            SportsScheduleUiState.Unavailable -> Message(stringResource(R.string.sports_schedule_unavailable), body)
            is SportsScheduleUiState.Empty -> Message(stringResource(if (s.filtered) R.string.sports_schedule_empty_sport else R.string.sports_schedule_empty), body)
            // A new day starts at the top; the same day keeps its place across rotation.
            is SportsScheduleUiState.Content -> key(day.toEpochDay()) { BoxWithConstraints(body) {
                val listState = rememberLazyListState()
                listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)
                val across = cardsAcross(maxWidth)
                val ctx = CardContext(byId, counts, profileId) { details.open(it) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                    item(key = "day") { SectionHeader(dayTitle(day), large = true) }
                    if (s.partial) item(key = "partial") { NoticeText(stringResource(R.string.sports_schedule_partial)) }
                    for (section in s.sections) {
                        item(key = "h:${section.leagueId}") { SectionHeader(section.league?.name ?: section.leagueId.uppercase()) }
                        gameRows(section.games, across, section.leagueId, ctx, headingDay = day)
                    }
                }
            } }
        }
    }

    details.current((state as? SportsScheduleUiState.Content)?.games.orEmpty())?.let { game ->
        GameDetailsSheet(game, byId[game.leagueId], vm, profileId, nav, onDismiss = details::close)
    }
}

@Composable
private fun LeaguesPane(
    vm: SportsHubViewModel,
    leagues: List<SportsLeague>,
    sport: String?,
    openLeagueId: String?,
    onOpenLeague: (String?) -> Unit,
    scrollToTop: SharedFlow<String>,
    nav: SportsHubNav,
    modifier: Modifier = Modifier,
) {
    val today by vm.today.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)
    if (openLeagueId != null) {
        val league = leagues.first { it.id == openLeagueId }
        LeagueGames(vm, league, onBack = { onOpenLeague(null) }, scrollToTop, nav, modifier)
        return
    }
    val groups = SportsLeaguesModel.groups(leagues, sport)
    when {
        groups.isNotEmpty() -> LazyColumn(state = listState, modifier = modifier, contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
            for (group in groups) {
                item(key = "s:${group.sport}") { SectionHeader(sportName(group.sport)) }
                items(group.leagues, key = { "l:" + it.id }) { league -> LeagueRow(league, onOpen = { onOpenLeague(league.id) }) }
            }
        }
        today == SportsTodayUiState.Loading -> LoadingBox(modifier)
        today == SportsTodayUiState.Unavailable -> Message(stringResource(R.string.sports_unavailable), modifier)
        else -> Message(stringResource(R.string.sports_leagues_empty), modifier)
    }
}

@Composable
private fun LeagueRow(league: SportsLeague, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(MobileDimens.CardCorner)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MobileDimens.TouchTarget)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = MobileDimens.GapMedium, vertical = MobileDimens.GapSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
    ) {
        Text(league.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (league.shortName != league.name) {
            Text(league.shortName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One league's games from yesterday to a week ahead, by day, with the same cards as Live & Today. */
@Composable
private fun LeagueGames(
    vm: SportsHubViewModel,
    league: SportsLeague,
    onBack: () -> Unit,
    scrollToTop: SharedFlow<String>,
    nav: SportsHubNav,
    modifier: Modifier,
) {
    BackHandler(onBack = onBack)
    val state by vm.leagueGames.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val details = rememberOpenGame()
    val leaguesById = remember(league) { mapOf(league.id to league) }

    Column(modifier) {
        FilterChipRow(
            labels = listOf(stringResource(R.string.sports_league_back), league.name),
            selectedIndex = 1,
            onSelect = { index -> if (index == 0) onBack() },
        )
        val body = Modifier.weight(1f).fillMaxWidth()
        when (val s = state) {
            SportsLeagueGamesUiState.Loading -> LoadingBox(body)
            SportsLeagueGamesUiState.Unavailable -> Message(stringResource(R.string.sports_schedule_unavailable), body)
            SportsLeagueGamesUiState.Empty -> Message(stringResource(R.string.sports_league_no_games), body)
            is SportsLeagueGamesUiState.Content -> key(league.id) { BoxWithConstraints(body) {
                val listState = rememberLazyListState()
                listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)
                val across = cardsAcross(maxWidth)
                val ctx = CardContext(leaguesById, counts, profileId) { details.open(it) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                    for (section in s.days) {
                        item(key = "d:${section.day}") { SectionHeader(dayTitle(section.day)) }
                        gameRows(section.games, across, section.day.toString(), ctx, headingDay = section.day)
                    }
                }
            } }
        }
    }

    details.current((state as? SportsLeagueGamesUiState.Content)?.games.orEmpty())?.let { game ->
        GameDetailsSheet(game, league, vm, profileId, nav, onDismiss = details::close)
    }
}

/** Today / Tomorrow / Yesterday, or the device-formatted date (e.g. "Sun, Nov 15"). */
@Composable
private fun dayChipLabel(day: LocalDate): String = relativeDay(day) ?: formatDay(day, "EEEMMMd")

@Composable
private fun dayTitle(day: LocalDate): String {
    val full = formatDay(day, "EEEEMMMMd")
    return relativeDay(day)?.let { stringResource(R.string.sports_day_time, it, full) } ?: full
}

@Composable
private fun relativeDay(day: LocalDate): String? {
    val zone = ZoneId.systemDefault()
    return when (SportsTimeLabels.day(day.atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis(), zone)) {
        SportsDayLabel.TODAY -> stringResource(R.string.sports_day_today)
        SportsDayLabel.TOMORROW -> stringResource(R.string.sports_day_tomorrow)
        SportsDayLabel.YESTERDAY -> stringResource(R.string.sports_day_yesterday)
        SportsDayLabel.OTHER -> null
    }
}

@Composable
private fun formatDay(day: LocalDate, skeleton: String): String {
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty) Locale.ROOT else locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
}

@Composable
private fun SectionHeader(text: String, large: Boolean = false) {
    Text(
        text = text,
        style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = MobileDimens.GapSmall, start = MobileDimens.GapTiny)
            .semantics { heading() },
    )
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = MobileDimens.GapTiny),
    )
}

@Composable
private fun GroupHeader(group: SportsTodayGroup) {
    Text(
        text = stringResource(
            when (group) {
                SportsTodayGroup.LIVE -> R.string.sports_group_live
                SportsTodayGroup.UPCOMING -> R.string.sports_group_upcoming
                SportsTodayGroup.RECENT -> R.string.sports_group_recent
            },
        ),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = MobileDimens.GapSmall, start = MobileDimens.GapTiny)
            .semantics { heading() },
    )
}

/** One game: league, the teams (or the event's title), the score or start, and channel availability. */
@Composable
private fun GameCard(
    event: SportsEvent,
    league: SportsLeague?,
    channelCount: Int?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** The day heading the card sits under, if any: its own day is then not repeated. */
    headingDay: LocalDate? = null,
) {
    val shape = RoundedCornerShape(MobileDimens.CardCorner)
    val detailsLabel = stringResource(R.string.sports_details_action)
    Column(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), shape)
            .clickable(onClickLabel = detailsLabel, onClick = onOpen)
            .padding(MobileDimens.GapSmall + MobileDimens.GapTiny),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
            Text(
                text = leagueLabel(event, league),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusLabel(event, headingDay)
        }
        val away = event.away
        val home = event.home
        if (away != null && home != null) {
            TeamLine(away, event)
            TeamLine(home, event)
        } else {
            Text(
                text = event.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChannelAvailability(event, channelCount)
    }
}

@Composable
private fun TeamLine(team: SportsTeam, event: SportsEvent) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
        TeamLogo(team)
        Text(
            text = team.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (event.showsScores) {
            Text(
                text = team.score.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The team's logo, or its abbreviation in a disc when there is none (or it fails to load). */
@Composable
private fun TeamLogo(team: SportsTeam) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier
                .size(LOGO_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (team.abbreviation ?: team.shortName ?: team.name).take(3),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // Decorative: the team's name sits right beside it.
    ChannelLogoImage(url = team.logoUrl, fallback = fallback, modifier = Modifier.size(LOGO_SIZE))
}

@Composable
private fun StatusLabel(event: SportsEvent, headingDay: LocalDate? = null) {
    when (event.status) {
        // The badge, then the game clock exactly as the backend reports it ("7:16 - 1st",
        // "Halftime", "Top 3rd") — nothing when it reports none; never a clock of our own.
        SportsEventStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.live_badge_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(LIVE_RED, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            SportsLiveClock.text(event)?.let { clock ->
                Text(
                    text = clock,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
        else -> Text(
            text = statusText(event, headingDay),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

/** Start time (local, labelled by day), or what the backend says about a game that isn't upcoming. */
@Composable
private fun statusText(event: SportsEvent, headingDay: LocalDate? = null): String = when (event.status) {
    SportsEventStatus.FINAL -> event.statusDetail ?: stringResource(R.string.sports_status_final)
    SportsEventStatus.POSTPONED -> stringResource(R.string.sports_status_postponed)
    SportsEventStatus.CANCELED -> stringResource(R.string.sports_status_canceled)
    SportsEventStatus.DELAYED -> event.statusDetail ?: stringResource(R.string.sports_status_delayed)
    SportsEventStatus.LIVE -> SportsLiveClock.text(event).orEmpty()
    SportsEventStatus.SCHEDULED, SportsEventStatus.UNKNOWN -> startLabel(event.startTimeMs, headingDay)
}

@Composable
private fun startLabel(startMs: Long, headingDay: LocalDate? = null): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val time = DateFormat.getTimeFormat(context).format(Date(startMs))
    val day = when (SportsStartLabel.day(startMs, System.currentTimeMillis(), zone, headingDay)) {
        null, SportsDayLabel.TODAY -> return time
        SportsDayLabel.TOMORROW -> stringResource(R.string.sports_day_tomorrow)
        SportsDayLabel.YESTERDAY -> stringResource(R.string.sports_day_yesterday)
        SportsDayLabel.OTHER -> DateFormat.getMediumDateFormat(context).format(Date(startMs))
    }
    return stringResource(R.string.sports_day_time, day, time)
}

@Composable
private fun ChannelAvailability(event: SportsEvent, count: Int?) {
    if (event.channels.isEmpty() || count == null) return // nothing matched, or still counting for this profile
    Text(
        text = if (count > 0) pluralStringResource(R.plurals.sports_channels_available, count, count) else stringResource(R.string.sports_no_channels),
        style = MaterialTheme.typography.labelMedium,
        color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun leagueLabel(event: SportsEvent, league: SportsLeague?): String = league?.shortName ?: event.leagueId.uppercase()

/** What, when, where, which networks, and Where to Watch: this profile's channels for it, each with Watch. */
@Composable
private fun GameDetailsSheet(
    event: SportsEvent,
    league: SportsLeague?,
    vm: SportsHubViewModel,
    profileId: Long,
    nav: SportsHubNav,
    onDismiss: () -> Unit,
) {
    val title = run {
        val away = event.away
        val home = event.home
        if (away != null && home != null) stringResource(R.string.sports_matchup, away.name, home.name) else event.title.orEmpty()
    }
    // Decided afresh when the panel opens, again if the active profile or the game's channels change
    // while it is open, and again after a Watch was refused (the list then shows what is allowed now).
    var recheck by remember(event.id) { mutableIntStateOf(0) }
    val offer by produceState<SportsChannelOffer?>(initialValue = null, event.id, event.channels, profileId, recheck) {
        value = null
        value = vm.channelsFor(event)
    }
    var refused by remember(event.id) { mutableStateOf<SportsWatchRejection?>(null) }
    var opening by remember(event.id) { mutableStateOf<SportsPendingAction?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val inFront = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    val multiview: MultiviewSession = koinInject()
    val multiviewPrefs: MultiviewPreferences = koinInject()
    val grid by multiview.model.collectAsStateWithLifecycle()
    val previewing by vm.previewing.collectAsStateWithLifecycle()
    var warnBeforeAdding by remember(event.id) { mutableStateOf<ChannelEntity?>(null) }
    val context = LocalContext.current
    val res = LocalResources.current

    // The panel's Preview lives only as long as the panel is on screen: closing it, leaving Sports for
    // another tab or Multiview, or the app going to the background stops it. A rotation does not (the
    // view model keeps it); a Watch has already handed it to the player, so nothing is stopped then.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) vm.stopPreview()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) vm.stopPreview()
        }
    }
    val streamOnScreen = LocalStreamOnScreen.current
    val previewShown = previewing != null
    DisposableEffect(previewShown) {
        // The mini player stays away while the panel shows the same stream.
        if (previewShown) streamOnScreen.value = true
        onDispose { if (previewShown) streamOnScreen.value = false }
    }

    /** One action at a time, decided again at that moment (see [SportsWatchLauncher.run]). */
    fun act(chosen: VisibleSportsChannel, action: SportsPanelAction) {
        val shown = offer ?: return
        if (opening != null) return
        opening = SportsPendingAction(chosen.localChannelId, action)
        refused = null
        scope.launch {
            val result = when (action) {
                SportsPanelAction.WATCH -> vm.watch(event, chosen, shown.profileId, title, inFront)
                SportsPanelAction.PREVIEW -> vm.preview(event, chosen, shown.profileId, title, inFront)
                SportsPanelAction.MULTIVIEW -> vm.checkForMultiview(event, chosen, shown.profileId, inFront)
            }
            opening = null
            if (result is SportsWatchCheck.Rejected) {
                refused = result.reason
                recheck++
                return@launch
            }
            if (result !is SportsWatchCheck.Allowed) return@launch
            when (action) {
                SportsPanelAction.WATCH -> nav.openPlayer()
                SportsPanelAction.PREVIEW -> Unit
                SportsPanelAction.MULTIVIEW -> {
                    val channel = result.channel
                    val key = MultiviewContent.Live(channel).key
                    val now = multiview.model.value
                    when (SportsMultiviewAdd.decide(now.contains(key), now.isFull) { multiviewPrefs.shouldWarnBeforeAdding(now.size) }) {
                        SportsMultiviewAdd.ADD -> addToMultiview(multiview, channel, context, res)
                        SportsMultiviewAdd.WARN_FIRST -> warnBeforeAdding = channel
                        SportsMultiviewAdd.ALREADY_THERE ->
                            Toast.makeText(context, res.getString(R.string.sports_multiview_already), Toast.LENGTH_SHORT).show()
                        SportsMultiviewAdd.FULL ->
                            Toast.makeText(context, res.getString(R.string.multiview_full, MultiviewModel.MAX_TILES), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    warnBeforeAdding?.let { channel ->
        PerformanceWarning(
            onContinue = {
                warnBeforeAdding = null
                addToMultiview(multiview, channel, context, res)
            },
            onDontShowAgain = {
                multiviewPrefs.suppressWarning()
                warnBeforeAdding = null
                addToMultiview(multiview, channel, context, res)
            },
            onCancel = { warnBeforeAdding = null },
        )
    }
    // A Preview that starts is brought into view: the details scroll to their top, where it plays, and
    // in landscape the sheet opens all the way. Remembered across rotation, so turning the phone does
    // not count as a new Preview.
    val detailsScroll = rememberScrollState()
    var revealedPreview by rememberSaveable(event.id) { mutableStateOf<Long?>(null) }
    var expandRequest by remember(event.id) { mutableIntStateOf(0) }
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    LaunchedEffect(previewing) {
        val reveal = SportsPreviewReveal.onChange(revealedPreview, previewing, landscape)
        revealedPreview = previewing
        if (reveal == null) return@LaunchedEffect
        if (reveal.expandSheet) expandRequest++
        if (reveal.scrollToTop) detailsScroll.animateScrollTo(0)
    }
    MobileBottomSheet(
        onDismissRequest = {
            vm.stopPreview()
            onDismiss()
        },
        title = title,
        expandRequest = expandRequest,
    ) {
        // The sheet is frosted glass; on the Samsung (1.0.7) the cards behind it still read through its
        // lower half, card glass inside it or not. So the details stand on an OPAQUE surface of the
        // theme's own colour: nothing behind shows through, whatever the glass setting or the brand.
        DetailsSurface(detailsScroll) {
            val previewName = offer?.channels?.firstOrNull { it.localChannelId == previewing }?.name
            previewing?.let { previewId ->
                SportsPreviewStage(
                    channelId = previewId,
                    channelName = previewName,
                    onFullscreen = { offer?.channels?.firstOrNull { it.localChannelId == previewing }?.let { act(it, SportsPanelAction.WATCH) } },
                    onStop = vm::stopPreview,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                Text(
                    leagueLabel(event, league),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusLabel(event)
            }
            val away = event.away
            val home = event.home
            if (away != null && home != null) {
                TeamLine(away, event)
                TeamLine(home, event)
            }
            if (event.status != SportsEventStatus.SCHEDULED && event.status != SportsEventStatus.UNKNOWN) {
                Text(startLabel(event.startTimeMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val networks = event.broadcasts.filter { !it.type.equals("RADIO", ignoreCase = true) }.map { it.name }.distinct()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = MobileDimens.GapTiny))
            WhereToWatch(
                event = event,
                offer = offer,
                refused = refused,
                opening = opening,
                previewing = previewing,
                inGrid = { id -> grid.tiles.any { (it.content as? MultiviewContent.Live)?.channel?.id == id } },
                onAction = ::act,
                onStopPreview = vm::stopPreview,
                onOpenMultiview = if (grid.isEmpty) null else {
                    {
                        vm.stopPreview()
                        nav.openMultiview()
                    }
                },
            )
            if (event.venueName != null || networks.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = MobileDimens.GapTiny))
                event.venueName?.let { DetailLine(stringResource(R.string.sports_details_venue), it) }
                if (networks.isNotEmpty()) DetailLine(stringResource(R.string.sports_details_on_tv), networks.joinToString())
            }
        }
    }
}

/**
 * The game's channels this profile may use, in the backend's ranking, each with Watch, Preview and Add
 * to Multiview. Shows names (as Live TV shows them), the channel's category underneath — so two feeds
 * with the same name can be told apart — and a 4K tag; never an address, a remote or EPG id, or a
 * match detail.
 */
@Composable
private fun WhereToWatch(
    event: SportsEvent,
    offer: SportsChannelOffer?,
    refused: SportsWatchRejection?,
    opening: SportsPendingAction?,
    previewing: Long?,
    inGrid: (Long) -> Boolean,
    onAction: (VisibleSportsChannel, SportsPanelAction) -> Unit,
    onStopPreview: () -> Unit,
    onOpenMultiview: (() -> Unit)?,
) {
    Text(
        text = stringResource(R.string.sports_where_to_watch),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    refused?.let { reason ->
        Text(
            text = stringResource(
                when (reason) {
                    SportsWatchRejection.PROFILE_CHANGED -> R.string.sports_watch_profile_changed
                    else -> R.string.sports_watch_unavailable
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    val message = when {
        event.channels.isEmpty() -> stringResource(R.string.sports_watch_no_matches)
        offer == null -> stringResource(R.string.sports_details_checking)
        offer.issue == SportsAccessIssue.SEVERAL_PLAYLISTS -> stringResource(R.string.sports_details_choose_playlist)
        offer.issue == SportsAccessIssue.NO_ELIGIBLE_PLAYLIST -> stringResource(R.string.sports_details_needs_xtream)
        offer.issue != null || offer.channels.isEmpty() -> stringResource(R.string.sports_details_none_allowed)
        else -> null
    }
    if (message != null || offer == null) {
        Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val labels = SportsChannelLabel.forRows(offer.channels)
    offer.channels.forEachIndexed { index, channel ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        WatchRow(
            channel = channel,
            label = labels[index],
            opening = opening?.takeIf { it.localChannelId == channel.localChannelId }?.action,
            busy = opening != null,
            previewing = previewing == channel.localChannelId,
            inGrid = inGrid(channel.localChannelId),
            onAction = { onAction(channel, it) },
            onStopPreview = onStopPreview,
        )
    }
    if (onOpenMultiview != null) {
        MobileButton(
            text = stringResource(R.string.multiview_open),
            onClick = onOpenMultiview,
            style = MobileButtonStyle.TEXT,
        )
    }
}

/**
 * One channel: its name and category, a 4K tag, and its three actions. The actions wrap onto as many
 * lines as the width needs, so all three stay whole and reachable on a narrow phone and in landscape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchRow(
    channel: VisibleSportsChannel,
    label: SportsChannelLabel,
    opening: SportsPanelAction?,
    busy: Boolean,
    previewing: Boolean,
    inGrid: Boolean,
    onAction: (SportsPanelAction) -> Unit,
    onStopPreview: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val number = label.number
                val secondary = when {
                    number != null && label.category != null -> stringResource(R.string.sports_channel_category_number, label.category, number)
                    number != null -> stringResource(R.string.sports_channel_number, number)
                    else -> label.category
                }
                secondary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (label.uhd) {
                Text(
                    text = stringResource(R.string.sports_watch_uhd),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapTiny),
        ) {
            val watchLabel = stringResource(R.string.sports_watch_a11y, label.name)
            MobileButton(
                text = stringResource(if (opening == SportsPanelAction.WATCH) R.string.sports_watch_opening else R.string.sports_watch),
                onClick = { onAction(SportsPanelAction.WATCH) },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = watchLabel },
            )
            if (previewing) {
                val stopLabel = stringResource(R.string.sports_preview_stop_a11y, label.name)
                MobileButton(
                    text = stringResource(R.string.sports_preview_stop),
                    onClick = onStopPreview,
                    style = MobileButtonStyle.SECONDARY,
                    modifier = Modifier.semantics { contentDescription = stopLabel },
                )
            } else {
                val previewLabel = stringResource(R.string.sports_preview_a11y, label.name)
                MobileButton(
                    text = stringResource(if (opening == SportsPanelAction.PREVIEW) R.string.sports_watch_opening else R.string.sports_preview),
                    onClick = { onAction(SportsPanelAction.PREVIEW) },
                    style = MobileButtonStyle.SECONDARY,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = previewLabel },
                )
            }
            val gridLabel = stringResource(if (inGrid) R.string.sports_multiview_in_grid_a11y else R.string.sports_multiview_add_a11y, label.name)
            MobileButton(
                text = stringResource(if (inGrid) R.string.sports_multiview_in_grid else R.string.multiview_add_to),
                onClick = { onAction(SportsPanelAction.MULTIVIEW) },
                style = MobileButtonStyle.TEXT,
                enabled = !busy && !inGrid,
                modifier = Modifier.semantics { contentDescription = gridLabel },
            )
        }
    }
}

/**
 * The Preview: the app's one stream, shown inside the game's panel (the same picture Live TV's preview
 * and the Sports Channels tab draw, from the same engine — ExoPlayer or mpv). A spinner until it plays,
 * the engine's own words and Retry when it fails, Stop, and full screen (which is Watch, carrying the
 * same stream on).
 */
@Composable
private fun SportsPreviewStage(channelId: Long, channelName: String?, onFullscreen: () -> Unit, onStop: () -> Unit) {
    val tuner: LiveTuner = koinInject()
    val tuned by tuner.channel.collectAsStateWithLifecycle()
    val engine by tuner.activeEngine.collectAsStateWithLifecycle()
    val isPlaying by engine.isPlaying.collectAsStateWithLifecycle()
    val failure by engine.error.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val showing = tuned?.id == channelId
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(MobileDimens.CardCorner))
            .background(Color.Black),
    ) {
        if (showing) VideoStage(player = tuner.player, modifier = Modifier.fillMaxSize())
        if (!showing || (!isPlaying && failure == null)) {
            val loading = stringResource(R.string.sports_loading)
            CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = loading }, color = Color.White)
        }
        val problem = failure
        if (showing && problem != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(MobileDimens.GapMedium)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(MobileDimens.GapSmall),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = problem.describe { id, args -> res.getString(id, *args.toTypedArray()) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                MobileButton(text = stringResource(R.string.common_retry), onClick = engine::retry, style = MobileButtonStyle.TEXT)
            }
        }
        if (showing && isPlaying) {
            Text(
                text = stringResource(R.string.live_badge_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(LIVE_RED, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val stopLabel = stringResource(R.string.sports_preview_stop_a11y, channelName.orEmpty())
        IconButton(
            onClick = onStop,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(MobileIcons.Close, contentDescription = stopLabel, tint = Color.White)
        }
        IconButton(
            onClick = onFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(MobileIcons.OpenInFull, contentDescription = stringResource(R.string.sports_open_fullscreen), tint = Color.White)
        }
    }
}

/**
 * The details' own surface: opaque, rounded like the app's cards, in the theme's high container colour
 * (made fully opaque over the background should a theme ever give it transparency), and scrollable
 * within a height cap — so venue, networks and channels stay reachable in landscape and at large font
 * sizes. Only this sheet uses it; every other sheet keeps its glass.
 */
@Composable
private fun DetailsSurface(scroll: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(MobileDimens.CardCorner)
    val fill = SportsDetailsSurface.fill(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.background)
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val maxHeight = with(density) {
        SportsDetailsSurface.maxHeightDp(window.width.toDp().value.toInt(), window.height.toDp().value.toInt()).dp
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), shape)
            .verticalScroll(scroll)
            .padding(horizontal = MobileDimens.GapMedium, vertical = MobileDimens.GapMedium),
        verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        content = content,
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StaleNotice() {
    Text(
        text = stringResource(R.string.sports_stale),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = MobileDimens.GapTiny),
    )
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val CARD_MIN_WIDTH = 300.dp
private val LOGO_SIZE = 28.dp
private val LIVE_RED = Color(0xFFDC3232)
