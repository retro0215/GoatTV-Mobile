package tv.own.owntv.mobile.ui.screens.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.rememberClockTick
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.util.Date

private const val BASE_MINUTE_DP = 3.2f // 30 mins = 96dp
private const val MINUTE_MS = 60_000L
private const val TICK_MINUTES = 30L
private val CHANNEL_COLUMN_WIDTH = 96.dp
private val ROW_HEIGHT = 54.dp
private val BLOCK_PADDING = 2.dp

/**
 * Synchronized EPG Timeline Grid:
 * Fixed channel/logo column on left, scrollable 30-minute time ruler and duration-proportional program blocks on right.
 */
@Composable
internal fun GuideGrid(
    vm: GuideViewModel,
    channels: LazyPagingItems<ChannelEntity>,
    listState: LazyListState,
    window: GuideWindow,
    densityPct: Int,
    isToday: Boolean,
    selectedProgramme: EpgProgrammeEntity?,
    onSelectProgramme: (ChannelEntity, EpgProgrammeEntity) -> Unit,
    onOpenChannel: (Long) -> Unit,
    onMenu: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision by vm.revision.collectAsStateWithLifecycle()
    val timeScroll = rememberScrollState()
    val minuteWidth = (BASE_MINUTE_DP * densityPct / 100f).dp
    val minutePx = with(LocalDensity.current) { minuteWidth.toPx() }
    val times = rememberGuideTimeFormat()
    val nowMs by rememberClockTick()

    // Auto-scroll horizontal timeline to current time on Today
    LaunchedEffect(window.start, isToday) {
        if (isToday && window.start > 0) {
            val minutes = ((System.currentTimeMillis() - window.start) / MINUTE_MS).coerceAtLeast(0L)
            val initialPx = (minutes * minutePx).toInt()
            timeScroll.scrollTo(initialPx)
        } else {
            timeScroll.scrollTo(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // --- TOP TIME RULER ROW ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Jump to Now / Header Button
            Box(
                modifier = Modifier
                    .width(CHANNEL_COLUMN_WIDTH)
                    .height(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        val minutes = ((System.currentTimeMillis() - window.start) / MINUTE_MS).coerceAtLeast(0L)
                        val targetPx = (minutes * minutePx).toInt()
                        timeScroll.dispatchRawDelta((targetPx - timeScroll.value).toFloat())
                    },
                ) {
                    Icon(
                        imageVector = MobileIcons.Schedule,
                        contentDescription = stringResource(R.string.content_epg_jump_now),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 30-Minute Time Ruler Ticks
            Row(modifier = Modifier.horizontalScroll(timeScroll)) {
                var tick = window.start
                while (tick < window.end) {
                    Text(
                        text = times.format(Date(tick)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(minuteWidth * TICK_MINUTES.toInt())
                            .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
                    )
                    tick += TICK_MINUTES * MINUTE_MS
                }
            }
        }
        HorizontalDivider()

        // --- CHANNEL & PROGRAM TIMELINE GRID ---
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                    val channel = channels[index]
                    if (channel != null) {
                        GuideGridRow(
                            vm = vm,
                            channel = channel,
                            window = window,
                            minuteWidth = minuteWidth,
                            timeScroll = timeScroll,
                            revision = revision,
                            nowMs = nowMs,
                            isToday = isToday,
                            selectedProgramme = selectedProgramme,
                            onSelectProgramme = onSelectProgramme,
                            onOpenChannel = onOpenChannel,
                            onMenu = onMenu,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }

            // Vertical Current-Time Line Indicator for TODAY
            if (isToday && nowMs in window.start..window.end) {
                val currentMinutes = (nowMs - window.start) / MINUTE_MS
                val currentPx = (currentMinutes * minutePx)
                val scrollOffset = timeScroll.value
                val lineX = CHANNEL_COLUMN_WIDTH + (currentPx - scrollOffset).dp

                if (lineX >= CHANNEL_COLUMN_WIDTH) {
                    Box(
                        modifier = Modifier
                            .padding(start = lineX)
                            .fillMaxHeight()
                            .width(2.dp)
                            .background(Color(0xFFDC3232)),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideGridRow(
    vm: GuideViewModel,
    channel: ChannelEntity,
    window: GuideWindow,
    minuteWidth: Dp,
    timeScroll: ScrollState,
    revision: Int,
    nowMs: Long,
    isToday: Boolean,
    selectedProgramme: EpgProgrammeEntity?,
    onSelectProgramme: (ChannelEntity, EpgProgrammeEntity) -> Unit,
    onOpenChannel: (Long) -> Unit,
    onMenu: (ChannelEntity) -> Unit,
) {
    var programmes by remember(channel.id, window.start, revision) {
        mutableStateOf(vm.cachedRow(channel.id))
    }
    LaunchedEffect(channel.id, window.start, revision) {
        if (programmes == null) {
            programmes = vm.row(channel)
        }
    }

    Row(modifier = Modifier.heightIn(min = ROW_HEIGHT)) {
        // FIXED LEFT CHANNEL COLUMN
        Surface(
            onClick = { onOpenChannel(channel.id) },
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = { onOpenChannel(channel.id) },
                        onLongClick = { onMenu(channel) },
                    )
                    .padding(horizontal = MobileDimens.GapTiny),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChannelLogo(channel)
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // SCROLLABLE PROGRAM TIMELINE
        Row(
            modifier = Modifier
                .horizontalScroll(timeScroll)
                .fillMaxHeight(),
        ) {
            val rows = programmes
            if (rows.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .width(minuteWidth * 180) // 3 hours fallback width
                        .fillMaxHeight()
                        .padding(BLOCK_PADDING)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.content_epg_no_guide),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                var cursor = window.start
                rows.forEach { programme ->
                    val start = programme.startMs.coerceAtLeast(window.start)
                    val end = programme.stopMs.coerceAtMost(window.end)
                    if (end <= cursor) return@forEach

                    // Spacer for gaps in schedule
                    if (start > cursor) {
                        val gapMinutes = ((start - cursor) / MINUTE_MS).toInt()
                        Spacer(modifier = Modifier.width(minuteWidth * gapMinutes))
                    }

                    val durationMinutes = (((end - start) / MINUTE_MS).coerceAtLeast(1L)).toInt()
                    val blockWidth = minuteWidth * durationMinutes
                    val isSelected = (selectedProgramme?.id == programme.id)
                    val isLive = isToday && (nowMs in programme.startMs..programme.stopMs)

                    ProgrammeBlock(
                        programme = programme,
                        width = blockWidth,
                        isSelected = isSelected,
                        isLive = isLive,
                        onClick = { onSelectProgramme(channel, programme) },
                    )
                    cursor = end
                }
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: EpgProgrammeEntity,
    width: Dp,
    isSelected: Boolean,
    isLive: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        isLive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(BLOCK_PADDING),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = programme.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected || isLive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
