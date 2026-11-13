package tv.own.owntv.mobile.ui.screens.sports.hub

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel
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
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * The Sports tab: a hub with Live & Today — games from the GoatTV Sports service, with scores and the
 * number of channels this profile can use for each — and Channels, the Sports screen the app already
 * had, hosted here unchanged (its preview, playback, Multiview and lifecycle are its own).
 *
 * Live & Today is read-only for now: a card opens a details panel, and nothing in it plays.
 */
@Composable
fun SportsHubScreen(
    scrollToTop: SharedFlow<String>,
    onOpenPlayer: () -> Unit,
    onOpenMultiview: (openPicker: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(SportsHubTab.LIVE_TODAY) }
    val labels = listOf(stringResource(R.string.sports_tab_live_today), stringResource(R.string.sports_tab_channels))
    Column(modifier.fillMaxSize()) {
        FilterChipRow(
            labels = labels,
            selectedIndex = tab.ordinal,
            onSelect = { index -> SportsHubTab.entries.getOrNull(index)?.let { tab = it } },
        )
        when (tab) {
            SportsHubTab.LIVE_TODAY -> LiveTodayPane(scrollToTop, Modifier.weight(1f).fillMaxWidth())
            SportsHubTab.CHANNELS -> SportsScreen(
                scrollToTop = scrollToTop,
                onOpenPlayer = onOpenPlayer,
                onOpenMultiview = onOpenMultiview,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LiveTodayPane(
    scrollToTop: SharedFlow<String>,
    modifier: Modifier = Modifier,
    vm: SportsHubViewModel = koinViewModel(),
) {
    // Polling follows this pane: composed only while Live & Today is the tab showing, and active only
    // while the screen is at least STARTED — leaving the tab, the Sports screen or the app stops it and
    // cancels any request in flight. The store allows one fetch at a time across every caller.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        vm.runPolling(lifecycle.currentStateFlow.map { sportsPollingActive(it.isAtLeast(Lifecycle.State.STARTED), SportsHubTab.LIVE_TODAY) })
    }

    val state by vm.today.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val profileId by vm.activeProfileId.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<SportsEvent?>(null) }
    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = MobileDestination.SPORTS.route, scrollToTop = scrollToTop)

    when (val s = state) {
        SportsTodayUiState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            val loading = stringResource(R.string.sports_loading)
            CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
        }
        SportsTodayUiState.Unavailable -> Message(stringResource(R.string.sports_unavailable), modifier)
        is SportsTodayUiState.Empty -> Column(modifier) {
            if (s.stale) StaleNotice()
            Message(stringResource(R.string.sports_today_empty), Modifier.weight(1f).fillMaxWidth())
        }
        is SportsTodayUiState.Content -> BoxWithConstraints(modifier) {
            // One card per row on a phone held upright, more across in landscape or on a tablet —
            // compact cards that never fill a landscape screen on their own.
            val across = ((maxWidth - MobileDimens.ScreenPaddingH * 2) / CARD_MIN_WIDTH).toInt().coerceIn(1, 3)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MobileDimens.ScreenPaddingH, end = MobileDimens.ScreenPaddingH, bottom = MobileDimens.GapMedium),
                verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            ) {
                if (s.stale) item(key = "stale") { StaleNotice() }
                for (section in s.sections) {
                    item(key = "h:${section.group}") { GroupHeader(section.group) }
                    items(section.games.chunked(across), key = { row -> "r:" + row.first().id }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                            for (game in row) {
                                GameCard(
                                    event = game,
                                    league = s.leagues[game.leagueId],
                                    channelCount = SportsChannelCounts.shown(counts, game.id, profileId),
                                    onOpen = { details = game },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    details?.let { game ->
        val league = (state as? SportsTodayUiState.Content)?.leagues?.get(game.leagueId)
        GameDetailsSheet(game, league, vm, profileId, onDismiss = { details = null })
    }
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
        SportsEventStatus.LIVE -> Text(
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
    SportsEventStatus.LIVE -> event.statusDetail.orEmpty()
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
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPaddingH, vertical = MobileDimens.GapSmall),
            verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall)) {
                Text(leagueLabel(event, league), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLabel(event)
            }
            if (event.status != SportsEventStatus.SCHEDULED && event.status != SportsEventStatus.UNKNOWN) {
                Text(startLabel(event.startTimeMs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val away = event.away
            val home = event.home
            if (away != null && home != null) {
                TeamLine(away, event)
                TeamLine(home, event)
            }
            event.venueName?.let { DetailLine(stringResource(R.string.sports_details_venue), it) }
            val networks = event.broadcasts.filter { !it.type.equals("RADIO", ignoreCase = true) }.map { it.name }.distinct()
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

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
