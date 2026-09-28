package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.live.serialize
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.BrowseCategorySheet
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.mobile.ui.components.MobileTextField
import tv.own.owntv.mobile.ui.components.mobileGroupPlate
import tv.own.owntv.mobile.ui.player.VideoStage
import tv.own.owntv.mobile.ui.screens.guide.rememberGuideTimeFormat
import tv.own.owntv.mobile.ui.shell.LocalStreamOnScreen
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.util.Date

/**
 * Mobile Live TV: A 16:9 Live Preview header with EPG program information, TV Guide & Categories
 * navigation actions, and a responsive 3-column visual channel grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    scrollToTop: SharedFlow<String>,
    onOpenChannel: (channelId: Long, openCatchup: Boolean) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    lockedKey: LiveKey? = null,
    onOpenGuide: (() -> Unit)? = null,
    vm: LiveViewModel = koinViewModel(),
) {
    val tuner: LiveTuner = koinInject()
    val multiviewSelection by tuner.multiviewSelection.collectAsStateWithLifecycle()
    val multiviewPending = multiviewSelection.isNotEmpty()

    if (lockedKey != null) {
        val pinned = lockedKey
        DisposableEffect(pinned) {
            vm.lock(pinned)
            onDispose { vm.unlock() }
        }
    }

    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val showNumbers by vm.showChannelNumbers.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()

    val tunedChannel by tuner.channel.collectAsStateWithLifecycle()
    val nowNext by tuner.nowNext.collectAsStateWithLifecycle()
    val isPlaying by tuner.player.isPlaying.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()

    var menuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showChannelSearch by remember { mutableStateOf(false) }
    var toFullscreen by remember { mutableStateOf(false) }

    val streamOnScreen = LocalStreamOnScreen.current
    DisposableEffect(tunedChannel) {
        streamOnScreen.value = (tunedChannel != null)
        onDispose { streamOnScreen.value = false }
    }

    // Live preview lifecycle management: dispose stops tuner unless navigating to fullscreen player
    DisposableEffect(Unit) {
        onDispose {
            if (!toFullscreen) {
                tuner.stop()
            }
        }
    }

    // Auto-tune first channel when Live TV opens if no channel is currently tuned
    LaunchedEffect(channels.itemCount) {
        if (tuner.channel.value == null && channels.itemCount > 0) {
            val firstChannel = channels.peek(0)
            if (firstChannel != null) {
                tuner.tune(firstChannel.id)
            }
        }
    }

    // EPG Now/Next loading for visible items in the grid
    LaunchedEffect(gridState, channels) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                vm.loadNowPlaying(indices.mapNotNull { channels.peek(it) })
            }
    }

    // Reset grid scroll when category changes
    LaunchedEffect(selected) {
        gridState.scrollToItem(0)
    }

    val selectedCategoryLabel = categories.firstOrNull { it.key == selected }?.label()
        ?: stringResource(R.string.content_category_all_channels)

    Column(modifier = modifier.fillMaxSize()) {
        // --- 1. TOP LIVE PREVIEW & HEADER AREA ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .mobileGroupPlate()
                .padding(bottom = MobileDimens.GapMedium),
        ) {
            // 1A. 16:9 PREVIEW VIDEO STAGE
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable {
                        toFullscreen = true
                        onOpenPlayer()
                    },
            ) {
                if (tunedChannel != null) {
                    VideoStage(player = tuner.player, modifier = Modifier.fillMaxSize())
                } else {
                    SelectAChannel()
                }

                // LIVE Badge Overlay
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xFFDC3232), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.live_badge_label),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                // Fullscreen Icon Button
                IconButton(
                    onClick = {
                        toFullscreen = true
                        onOpenPlayer()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        imageVector = MobileIcons.OpenInFull,
                        contentDescription = stringResource(R.string.live_categories_button),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 1B. TUNED CHANNEL & PROGRAM INFORMATION
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MobileDimens.PagePaddingH, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapMedium),
            ) {
                ChannelLogoImage(
                    url = tunedChannel?.displayLogoUrl,
                    modifier = Modifier.size(40.dp),
                    fallback = {
                        Icon(
                            imageVector = MobileIcons.LiveTv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    },
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tunedChannel?.name ?: stringResource(R.string.content_preview_select_channel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val prog = nowNext?.now
                    if (prog != null && prog.title.isNotBlank()) {
                        Text(
                            text = prog.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val timeFmt = rememberGuideTimeFormat()
                        val timeStr = timeFmt.format(Date(prog.startMs))
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 1C. ACTION BUTTONS: [ TV GUIDE ] [ CATEGORIES ] [ SEARCH ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MobileDimens.PagePaddingH),
                horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapMedium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onOpenGuide?.invoke() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = MobileIcons.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(R.string.live_tv_guide_button))
                }

                FilledTonalButton(
                    onClick = { showCategorySheet = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = MobileIcons.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(R.string.live_categories_button))
                }

                IconButton(
                    onClick = { showChannelSearch = !showChannelSearch },
                ) {
                    Icon(
                        imageVector = if (showChannelSearch) MobileIcons.Close else MobileIcons.Search,
                        contentDescription = stringResource(R.string.live_search_channels),
                    )
                }
            }

            // Optional Channel Search Bar
            if (showChannelSearch) {
                MobileTextField(
                    value = searchQuery,
                    onValueChange = { vm.setSearchQuery(it) },
                    label = stringResource(R.string.live_search_channels),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MobileDimens.PagePaddingH, vertical = 4.dp),
                )
            }
        }

        // --- 2. CURRENT CATEGORY HEADING ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MobileDimens.PagePaddingH, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selectedCategoryLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // --- 3. CHANNEL GRID (3-COLUMN VISUAL GRID) ---
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (channels.itemCount == 0) {
                EmptyChannels()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    state = gridState,
                    contentPadding = PaddingValues(
                        horizontal = MobileDimens.PagePaddingH,
                        vertical = MobileDimens.GapSmall,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
                    verticalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val channel = channels[index]
                        if (channel != null) {
                            val isSelected = (channel.id == tunedChannel?.id)
                            ChannelGridTile(
                                channel = channel,
                                number = channel.number?.takeIf { showNumbers && it > 0 },
                                isSelected = isSelected,
                                isFavorite = channel.id in favoriteIds,
                                onClick = {
                                    if (multiviewPending) {
                                        tuner.selectWithoutPlaying(channel)
                                        onOpenPlayer()
                                    } else if (isSelected) {
                                        toFullscreen = true
                                        onOpenPlayer()
                                    } else {
                                        tuner.tune(channel.id)
                                    }
                                },
                                onLongClick = { menuFor = channel },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCategorySheet) {
        CategoryBrowserSheet(
            categories = categories,
            selectedKey = selected,
            onSelect = { key -> vm.select(key) },
            onDismiss = { showCategorySheet = false },
        )
    }

    menuFor?.let { channel ->
        ChannelMenu(
            channel = channel,
            selected = selected,
            isFavorite = channel.id in favoriteIds,
            originName = categories.firstOrNull { it.key == selected }?.label().orEmpty(),
            vm = vm,
            onOpenCatchup = { onOpenChannel(channel.id, true) },
            onDismiss = { menuFor = null },
        )
    }
}

/** Visual Grid Tile for Channel */
@Composable
private fun ChannelGridTile(
    channel: ChannelEntity,
    number: Int?,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.95f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (channel.catchup) {
                    Icon(
                        imageVector = MobileIcons.History,
                        contentDescription = stringResource(R.string.content_catchup),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Spacer(Modifier.size(14.dp))
                }
                if (isFavorite) {
                    Icon(
                        imageVector = MobileIcons.Star,
                        contentDescription = stringResource(R.string.content_category_favorites),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            ) {
                ChannelLogoImage(
                    url = channel.displayLogoUrl,
                    modifier = Modifier.size(42.dp),
                    fallback = {
                        Icon(
                            imageVector = MobileIcons.LiveTv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Vertical Category Browser Bottom Sheet with Search */
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

/** Category Label resolution helper */
@Composable
private fun LiveCategory.label(): String = when (builtIn) {
    LiveCategory.BuiltIn.ALL -> stringResource(R.string.content_category_all_channels)
    LiveCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    LiveCategory.BuiltIn.HISTORY -> stringResource(R.string.content_category_history)
    LiveCategory.BuiltIn.CATCHUP -> stringResource(R.string.content_catchup)
    null -> title.orEmpty()
}

@Composable
private fun SelectAChannel() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_preview_select_channel),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyChannels() {
    Box(Modifier.fillMaxSize().padding(MobileDimens.GapLarge), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.content_no_channels_here),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
