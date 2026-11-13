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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.sports.live.SportsAccessIssue
import tv.own.owntv.mobile.sports.live.SportsChannelOffer
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsEventStatus
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.sports.live.SportsTeam
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.FilterChipRow
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.nav.MobileDestination
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.sports.SportsScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.mobile.ui.theme.glassSurface
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
 * tabs and kept across rotation. The schedule tabs are read-only for now: a card opens a details panel,
 * and nothing in it plays.
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
            SportSelector(sports, sport, onSelect = { selection = selection.copy(sport = it) })
        }
        val pane = Modifier.weight(1f).fillMaxWidth()
        when (selection.tab) {
            SportsHubTab.LIVE_TODAY -> LiveTodayPane(vm, scrollToTop, pane)
            SportsHubTab.SCHEDULE -> SchedulePane(vm, days, day, onSelectDay = { selection = selection.copy(epochDay = it.toEpochDay()) }, scrollToTop, pane)
            SportsHubTab.LEAGUES -> LeaguesPane(
                vm = vm,
                leagues = leagues,
                sport = sport,
                openLeagueId = leagueId,
                onOpenLeague = { selection = selection.copy(leagueId = it) },
                scrollToTop = scrollToTop,
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
private class CardContext(
    val leagues: Map<String, SportsLeague>,
    val counts: SportsChannelCounts,
    val profileId: Long,
    val onOpen: (SportsEvent) -> Unit,
)

/** [games] as rows of [across] cards. [keyPrefix] keeps keys unique across sections. */
private fun LazyListScope.gameRows(games: List<SportsEvent>, across: Int, keyPrefix: String, ctx: CardContext) {
    items(games.chunked(across), key = { row -> "r:$keyPrefix:" + row.first().id }) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
            for (game in row) {
                GameCard(
                    event = game,
                    league = ctx.leagues[game.leagueId],
                    channelCount = SportsChannelCounts.shown(ctx.counts, game.id, ctx.profileId),
                    onOpen = { ctx.onOpen(game) },
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
private fun LiveTodayPane(vm: SportsHubViewModel, scrollToTop: SharedFlow<String>, modifier: Modifier = Modifier) {
    val state by vm.today.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val byId = remember(leagues) { leagues.associateBy { it.id } }
    var details by remember { mutableStateOf<SportsEvent?>(null) }
    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)

    when (val s = state) {
        SportsTodayUiState.Loading -> LoadingBox(modifier)
        SportsTodayUiState.Unavailable -> Message(stringResource(R.string.sports_unavailable), modifier)
        is SportsTodayUiState.Empty -> Column(modifier) {
            if (s.stale) StaleNotice()
            Message(stringResource(if (s.filtered) R.string.sports_today_empty_sport else R.string.sports_today_empty), Modifier.weight(1f).fillMaxWidth())
        }
        is SportsTodayUiState.Content -> BoxWithConstraints(modifier) {
            val across = cardsAcross(maxWidth)
            val ctx = CardContext(s.leagues, counts, profileId) { details = it }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                if (s.stale) item(key = "stale") { StaleNotice() }
                for (section in s.sections) {
                    item(key = "h:${section.group}") { GroupHeader(section.group) }
                    gameRows(section.games, across, section.group.name, ctx)
                }
            }
        }
    }

    details?.let { opened ->
        // The open panel follows the game: its clock and score update with each refresh.
        val game = SportsLiveClock.latest(opened, (state as? SportsTodayUiState.Content)?.sections?.flatMap { it.games }.orEmpty())
        GameDetailsSheet(game, byId[game.leagueId], vm, profileId, onDismiss = { details = null })
    }
}

@Composable
private fun SchedulePane(
    vm: SportsHubViewModel,
    days: List<LocalDate>,
    day: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    scrollToTop: SharedFlow<String>,
    modifier: Modifier = Modifier,
) {
    val state by vm.schedule.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    val leagues by vm.leagues.collectAsStateWithLifecycle()
    val byId = remember(leagues) { leagues.associateBy { it.id } }
    var details by remember { mutableStateOf<SportsEvent?>(null) }
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
                val ctx = CardContext(byId, counts, profileId) { details = it }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                    item(key = "day") { SectionHeader(dayTitle(day), large = true) }
                    if (s.partial) item(key = "partial") { NoticeText(stringResource(R.string.sports_schedule_partial)) }
                    for (section in s.sections) {
                        item(key = "h:${section.leagueId}") { SectionHeader(section.league?.name ?: section.leagueId.uppercase()) }
                        gameRows(section.games, across, section.leagueId, ctx)
                    }
                }
            } }
        }
    }

    details?.let { opened ->
        val game = SportsLiveClock.latest(opened, (state as? SportsScheduleUiState.Content)?.games.orEmpty())
        GameDetailsSheet(game, byId[game.leagueId], vm, profileId, onDismiss = { details = null })
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
    modifier: Modifier = Modifier,
) {
    val today by vm.today.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)
    if (openLeagueId != null) {
        val league = leagues.first { it.id == openLeagueId }
        LeagueGames(vm, league, onBack = { onOpenLeague(null) }, scrollToTop, modifier)
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
private fun LeagueGames(vm: SportsHubViewModel, league: SportsLeague, onBack: () -> Unit, scrollToTop: SharedFlow<String>, modifier: Modifier) {
    BackHandler(onBack = onBack)
    val state by vm.leagueGames.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<SportsEvent?>(null) }
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
                val ctx = CardContext(leaguesById, counts, profileId) { details = it }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                    for (section in s.days) {
                        item(key = "d:${section.day}") { SectionHeader(dayTitle(section.day)) }
                        gameRows(section.games, across, section.day.toString(), ctx)
                    }
                }
            } }
        }
    }

    details?.let { opened ->
        val game = SportsLiveClock.latest(opened, (state as? SportsLeagueGamesUiState.Content)?.games.orEmpty())
        GameDetailsSheet(game, league, vm, profileId, onDismiss = { details = null })
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
            StatusLabel(event)
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
private fun StatusLabel(event: SportsEvent) {
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
            text = statusText(event),
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
private fun statusText(event: SportsEvent): String = when (event.status) {
    SportsEventStatus.FINAL -> event.statusDetail ?: stringResource(R.string.sports_status_final)
    SportsEventStatus.POSTPONED -> stringResource(R.string.sports_status_postponed)
    SportsEventStatus.CANCELED -> stringResource(R.string.sports_status_canceled)
    SportsEventStatus.DELAYED -> event.statusDetail ?: stringResource(R.string.sports_status_delayed)
    SportsEventStatus.LIVE -> SportsLiveClock.text(event).orEmpty()
    SportsEventStatus.SCHEDULED, SportsEventStatus.UNKNOWN -> startLabel(event.startTimeMs)
}

@Composable
private fun startLabel(startMs: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val time = DateFormat.getTimeFormat(context).format(Date(startMs))
    val day = when (SportsTimeLabels.day(startMs, System.currentTimeMillis(), zone)) {
        SportsDayLabel.TODAY -> return time
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

/** Read-only: what, when, where, which networks, and which of this profile's channels carry it. */
@Composable
private fun GameDetailsSheet(event: SportsEvent, league: SportsLeague?, vm: SportsHubViewModel, profileId: Long, onDismiss: () -> Unit) {
    val title = run {
        val away = event.away
        val home = event.home
        if (away != null && home != null) stringResource(R.string.sports_matchup, away.name, home.name) else event.title.orEmpty()
    }
    // Decided afresh when the panel opens, and again if the active profile changes while it is open.
    val offer by produceState<SportsChannelOffer?>(initialValue = null, event.id, event.channels, profileId) {
        value = null
        value = vm.channelsFor(event)
    }
    MobileBottomSheet(onDismissRequest = onDismiss, title = title) {
        // The sheet is frosted glass over whatever is behind it; the details sit on the theme's card
        // glass inside it — the darker, unfrosted layer cards use — so scores and names stay legible
        // over a busy wallpaper or a live preview.
        DetailsPanel {
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
        }
        val networks = event.broadcasts.filter { !it.type.equals("RADIO", ignoreCase = true) }.map { it.name }.distinct()
        if (event.venueName != null || networks.isNotEmpty() || event.channels.isNotEmpty()) {
            DetailsPanel {
                event.venueName?.let { DetailLine(stringResource(R.string.sports_details_venue), it) }
                if (networks.isNotEmpty()) DetailLine(stringResource(R.string.sports_details_on_tv), networks.joinToString())
                if (event.channels.isNotEmpty()) {
                    val o = offer
                    val yours = when {
                        o == null -> stringResource(R.string.sports_details_checking)
                        o.issue == SportsAccessIssue.SEVERAL_PLAYLISTS -> stringResource(R.string.sports_details_choose_playlist)
                        o.issue == SportsAccessIssue.NO_ELIGIBLE_PLAYLIST -> stringResource(R.string.sports_details_needs_xtream)
                        o.issue != null || o.channels.isEmpty() -> stringResource(R.string.sports_details_none_allowed)
                        else -> o.channels.joinToString { it.name }
                    }
                    DetailLine(stringResource(R.string.sports_details_your_channels), yours)
                }
            }
        }
    }
}

@Composable
private fun DetailsPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassSurface(GlassSurface.CARDS, RoundedCornerShape(MobileDimens.CardCorner))
            .padding(horizontal = MobileDimens.GapMedium, vertical = MobileDimens.GapSmall + MobileDimens.GapTiny),
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
