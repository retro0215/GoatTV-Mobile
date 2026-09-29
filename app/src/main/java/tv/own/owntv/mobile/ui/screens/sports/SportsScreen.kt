package tv.own.owntv.mobile.ui.screens.sports

import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.SharedFlow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.mobileGroupPlate
import tv.own.owntv.mobile.ui.player.VideoStage
import tv.own.owntv.mobile.ui.screens.ObeyScrollToTop
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.mobile.ui.screens.live.addToMultiview
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewContent
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewPreferences
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewSession
import tv.own.owntv.mobile.ui.screens.multiview.PerformanceWarning
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens
import tv.own.owntv.player.describe

/**
 * Sports: the television's section on a phone.
 *
 * A 16:9 preview, with sound, over the playlist's sports channels, one row per league in the
 * television's order. The first tap on a card previews it, a second tap — or a tap on the preview —
 * watches it full screen, and Back from the player comes back here. A long press keeps the channel for
 * Multiview or opens the grid.
 *
 * Side by side when the screen is wide and short (a phone on its side, a tablet), stacked otherwise:
 * one layout, arranged two ways.
 */
@OptIn(UnstableApi::class)
@Composable
fun SportsScreen(
    scrollToTop: SharedFlow<String>,
    onOpenPlayer: () -> Unit,
    onOpenMultiview: (openPicker: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vm: SportsViewModel = koinViewModel(),
) {
    val tuner: LiveTuner = koinInject()
    val multiview: MultiviewSession = koinInject()
    val multiviewPrefs: MultiviewPreferences = koinInject()

    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val tunedChannel by tuner.channel.collectAsStateWithLifecycle()
    val grid by multiview.model.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    listState.ObeyScrollToTop(route = SPORTS_ROUTE, scrollToTop = scrollToTop)

    var toFullscreen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var pendingWarnedAdd by remember { mutableStateOf<ChannelEntity?>(null) }

    val sections = (state as? SportsUiState.Ready)?.sections.orEmpty()
    val selectedChannel = selected?.let { sel ->
        sections.firstOrNull { it.section == sel.section }?.channels?.firstOrNull { it.id == sel.channelId }
    } ?: tunedChannel?.takeIf { it.id == selected?.channelId }
    val showingStream = tunedChannel != null && tunedChannel?.id == selected?.channelId

    // The mini player stays away while the preview is the stream: it would be showing the same picture.
    val streamOnScreen = LocalStreamOnScreen.current
    DisposableEffect(showingStream) {
        streamOnScreen.value = showingStream
        onDispose { streamOnScreen.value = false }
    }

    // Back on screen — from the player, Multiview, another tab or another app — once the page is
    // settled in front, so whatever it replaced has let go of the tuner before the preview asks for it.
    // Stopped — another tab, Multiview, the Android Home button — the preview ends with it.
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val resumed = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_STOP -> false
                else -> return@LifecycleEventObserver
            }
            when (sportsPageEvent(resumed, toFullscreen, activity?.isChangingConfigurations == true)) {
                SportsPageEvent.SHOW -> vm.onPageShown()
                SportsPageEvent.LEAVE -> vm.onPageLeft()
                SportsPageEvent.NOTHING -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!toFullscreen) vm.onPageLeft()
        }
    }

    val labels = SportsSection.entries.associateWith { stringResource(it.labelRes) }
    val openFullscreen = {
        if (selected != null) {
            vm.prepareFullscreen()
            toFullscreen = true
            onOpenPlayer()
        }
    }
    val onTap: (SportsSectionData, ChannelEntity) -> Unit = { data, channel ->
        val zap = LiveTuner.ZapList(labels.getValue(data.section), data.channels)
        if (vm.onChannelTapped(data.section, channel, zap) == SportsTap.FULLSCREEN) openFullscreen()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_MIN_WIDTH && maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(PREVIEW_WEIGHT)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    PreviewPane(tuner, selectedChannel, selected?.section, showingStream, openFullscreen)
                }
                SectionsPane(
                    state = state,
                    selectedId = selected?.channelId,
                    listState = listState,
                    onTap = onTap,
                    onLongPress = { menuFor = it },
                    modifier = Modifier.weight(1f - PREVIEW_WEIGHT).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PreviewPane(tuner, selectedChannel, selected?.section, showingStream, openFullscreen)
                SectionsPane(
                    state = state,
                    selectedId = selected?.channelId,
                    listState = listState,
                    onTap = onTap,
                    onLongPress = { menuFor = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

    val context = LocalContext.current
    val res = LocalResources.current
    menuFor?.let { channel ->
        val key = MultiviewContent.Live(channel).key
        val inGrid = grid.contains(key)
        MobileBottomSheet(onDismissRequest = { menuFor = null }, title = channel.name) {
            if (inGrid) {
                MobileListRow(
                    title = stringResource(R.string.sports_remove_from_multiview),
                    leading = { Icon(MobileIcons.GridView, contentDescription = null) },
                    onClick = {
                        menuFor = null
                        grid.slotOf(key)?.let(multiview::remove)
                    },
                )
            } else {
                MobileListRow(
                    title = stringResource(R.string.multiview_add_to),
                    leading = { Icon(MobileIcons.GridView, contentDescription = null) },
                    onClick = {
                        menuFor = null
                        // The fifth tile asks first; a full grid says so. Both are the grid's own rules.
                        if (multiviewPrefs.shouldWarnBeforeAdding(multiview.model.value.size)) {
                            pendingWarnedAdd = channel
                        } else {
                            addToMultiview(multiview, channel, context, res)
                        }
                    },
                )
            }
            MobileListRow(
                title = stringResource(R.string.multiview_open),
                leading = { Icon(MobileIcons.OpenInFull, contentDescription = null) },
                onClick = {
                    menuFor = null
                    // The grid needs the connection the preview holds, and must not play over it.
                    vm.onPageLeft()
                    onOpenMultiview(multiview.model.value.isEmpty)
                },
            )
            MobileListRow(
                title = stringResource(R.string.content_close),
                leading = { Icon(MobileIcons.Close, contentDescription = null) },
                onClick = { menuFor = null },
            )
        }
    }

    pendingWarnedAdd?.let { channel ->
        PerformanceWarning(
            onContinue = {
                pendingWarnedAdd = null
                addToMultiview(multiview, channel, context, res)
            },
            onDontShowAgain = {
                multiviewPrefs.suppressWarning()
                pendingWarnedAdd = null
                addToMultiview(multiview, channel, context, res)
            },
            onCancel = { pendingWarnedAdd = null },
        )
    }
}

/** The 16:9 preview and the line under it naming what it shows. */
@Composable
private fun PreviewPane(
    tuner: LiveTuner,
    channel: ChannelEntity?,
    section: SportsSection?,
    showingStream: Boolean,
    onFullscreen: () -> Unit,
) {
    val engine by tuner.activeEngine.collectAsStateWithLifecycle()
    val isPlaying by engine.isPlaying.collectAsStateWithLifecycle()
    val failure by engine.error.collectAsStateWithLifecycle()
    val res = LocalResources.current

    Column(
        Modifier
            .fillMaxWidth()
            .mobileGroupPlate()
            .padding(bottom = MobileDimens.GapSmall),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable(enabled = channel != null, onClick = onFullscreen),
        ) {
            when {
                showingStream -> VideoStage(player = tuner.player, modifier = Modifier.fillMaxSize())
                channel != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ChannelLogo(channel, Modifier.size(PREVIEW_LOGO))
                    CircularProgressIndicator(Modifier.size(PREVIEW_LOGO + 16.dp), color = Color.White)
                }
                else -> Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.sports_preview_prompt),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // The stream failed: core's own words for why, and the engine's retry. The rest of the page
            // is untouched and another card can be tapped at any time.
            val problem = failure
            if (showingStream && problem != null) {
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
                    TextButton(onClick = engine::retry) { Text(stringResource(R.string.common_retry)) }
                }
            }

            if (showingStream && isPlaying) {
                Text(
                    text = stringResource(R.string.live_badge_label),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(LIVE_RED, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            if (channel != null) {
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        imageVector = MobileIcons.OpenInFull,
                        contentDescription = stringResource(R.string.sports_open_fullscreen),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (channel != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MobileDimens.PagePaddingH, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
            ) {
                ChannelLogo(channel, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (section != null) {
                        Text(
                            text = stringResource(section.labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** The leagues, one row each, or what to say when there are none. */
@Composable
private fun SectionsPane(
    state: SportsUiState,
    selectedId: Long?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onTap: (SportsSectionData, ChannelEntity) -> Unit,
    onLongPress: (ChannelEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SportsUiState.Loading -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        SportsUiState.Empty -> Message(stringResource(R.string.sports_empty_message), modifier)
        SportsUiState.Failed -> Message(stringResource(R.string.sports_load_failed), modifier)
        is SportsUiState.Ready -> BoxWithConstraints(modifier) {
            // Two and a bit cards across a phone, so the row plainly scrolls; never one card per screen.
            val cardWidth = ((maxWidth - MobileDimens.PagePaddingH * 2) / CARDS_ACROSS).coerceIn(CARD_MIN, CARD_MAX)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = MobileDimens.GapMedium),
            ) {
                items(state.sections, key = { it.section.name }) { data ->
                    SectionRow(data, selectedId, cardWidth, onTap, onLongPress)
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    data: SportsSectionData,
    selectedId: Long?,
    cardWidth: Dp,
    onTap: (SportsSectionData, ChannelEntity) -> Unit,
    onLongPress: (ChannelEntity) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = MobileDimens.GapSmall)) {
        Text(
            text = stringResource(data.section.labelRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = MobileDimens.PagePaddingH + 4.dp, vertical = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = MobileDimens.PagePaddingH),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(data.channels, key = { it.id }) { channel ->
                SportsChannelCard(
                    channel = channel,
                    selected = channel.id == selectedId,
                    width = cardWidth,
                    onClick = { onTap(data, channel) },
                    onLongClick = { onLongPress(channel) },
                )
            }
        }
    }
}

/** The television's card: the provider's logo in a 16:10 plate, the provider's name under it. */
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SportsChannelCard(
    channel: ChannelEntity,
    selected: Boolean,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
    Column(
        Modifier
            .width(width)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(shape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                )
                .border(border, shape),
            contentAlignment = Alignment.Center,
        ) {
            ChannelLogo(channel, Modifier.size(CARD_LOGO))
        }
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ChannelLogo(channel: ChannelEntity, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ChannelLogoImage(
            url = channel.displayLogoUrl,
            modifier = Modifier.fillMaxSize(),
            fallback = {
                Icon(
                    imageVector = MobileIcons.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize(0.8f),
                )
            },
        )
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier.padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The route, as the shell names it for scroll-to-top. */
private const val SPORTS_ROUTE = "sports"

/** Side by side only when there is room across and the screen is on its side. */
private val WIDE_MIN_WIDTH = 600.dp
private const val PREVIEW_WEIGHT = 0.45f
private const val CARDS_ACROSS = 2.3f
private val CARD_MIN = 128.dp
private val CARD_MAX = 180.dp
private val CARD_LOGO = 56.dp
private val PREVIEW_LOGO = 72.dp
private val LIVE_RED = Color(0xFFDC3232)
