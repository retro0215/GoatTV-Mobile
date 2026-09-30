package tv.own.owntv.mobile.ui.screens.multiview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.ChannelLogoImage
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.screens.live.LiveCategory
import tv.own.owntv.mobile.ui.screens.live.LiveViewModel

/**
 * AerioTV's Add to Multiview, on this app's own Live TV data: a header with Cancel and Done, the
 * category chips — All, Favourites, Recent and the provider's folders, in the profile's order — a
 * search toggle left of the chips, and the channel rows with logo, name and what is on now.
 *
 * It runs on a Live TV view model of its own, chosen with `lock`, so browsing here never changes the
 * category the Live TV tab remembers. Hidden channels, renames and kids' filters all apply, and the
 * search is the same local query the Live tab runs — nothing asks the provider per keystroke.
 *
 * Tapping a channel adds it, or takes it out if it is already there: a channel is never in two tiles
 * (AerioTV's rule). With [swapTargetSlot] set, a tap re-points that tile instead and closes the sheet;
 * a channel another tile already shows cannot be picked. Cancel puts the grid back as it was when the
 * sheet opened; Done, Back and a swipe keep what was chosen.
 */
@UnstableApi
@Composable
fun AddToMultiviewSheet(
    session: MultiviewSession,
    swapTargetSlot: Int?,
    /** Every add passes through this, so the fifth tile can ask first. */
    gatedAdd: (() -> Unit) -> Unit,
    onDismiss: () -> Unit,
    vm: LiveViewModel = koinViewModel(key = PICKER_VM_KEY),
) {
    val model by session.model.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedKey by vm.selected.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val channels = vm.channels.collectAsLazyPagingItems()
    val snapshot = remember { session.model.value }
    var searchActive by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.lock(LiveKey.All) }
    LaunchedEffect(listState, channels) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                vm.loadNowPlaying(channelRowsToPeek(indices, channels.itemCount).mapNotNull { channels.peek(it) })
            }
    }

    val swapping = swapTargetSlot != null
    val chips = categories.filter { it.builtIn != LiveCategory.BuiltIn.CATCHUP }

    MobileBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                if (!swapping) session.restore(snapshot)
                onDismiss()
            }) {
                Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(if (swapping) R.string.multiview_tile_change_channel else R.string.multiview_add_to),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (swapping) stringResource(R.string.common_done)
                    else stringResource(R.string.multiview_picker_done, model.size),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = {
                searchActive = !searchActive
                if (!searchActive) vm.setSearchQuery("")
            }) {
                Icon(
                    imageVector = if (searchActive) MobileIcons.Close else MobileIcons.Search,
                    contentDescription = stringResource(
                        if (searchActive) R.string.multiview_picker_close_search else R.string.multiview_picker_search,
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (searchActive) {
                // No autofocus: the keyboard opens when the field is tapped, never on its own.
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setSearchQuery,
                    placeholder = { Text(stringResource(R.string.multiview_picker_search)) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setSearchQuery("") }) {
                                Icon(MobileIcons.Close, contentDescription = stringResource(R.string.multiview_picker_close_search))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyRow(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chips, key = { it.key.toString() }) { category ->
                        FilterChip(
                            selected = category.key == selectedKey,
                            onClick = { vm.lock(category.key) },
                            label = { Text(category.chipLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(PICKER_LIST_HEIGHT),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (channels.itemCount == 0) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.multiview_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                val channel = channels[index] ?: return@items
                val content = MultiviewContent.Live(channel)
                val inGrid = model.contains(content.key)
                val isSwapTarget = swapTargetSlot != null && model.slotOf(content.key) == swapTargetSlot
                ChannelRow(
                    channel = channel,
                    nowTitle = nowPlaying[channel.id],
                    inGrid = inGrid,
                    enabled = when {
                        swapping -> !inGrid || isSwapTarget
                        else -> inGrid || !model.isFull
                    },
                    onClick = {
                        when {
                            swapTargetSlot != null -> if (session.replace(swapTargetSlot, content)) onDismiss()
                            inGrid -> model.slotOf(content.key)?.let(session::remove)
                            !model.isFull -> gatedAdd { session.add(content) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntity,
    nowTitle: String?,
    inGrid: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChannelLogoImage(
            url = channel.displayLogoUrl,
            modifier = Modifier.size(40.dp),
            fallback = {
                Icon(
                    imageVector = MobileIcons.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!nowTitle.isNullOrBlank()) {
                Text(
                    text = nowTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (inGrid) MobileIcons.CheckCircle else MobileIcons.Add,
                contentDescription = null,
                tint = when {
                    inGrid -> MaterialTheme.colorScheme.tertiary
                    enabled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}

@Composable
private fun LiveCategory.chipLabel(): String = when (builtIn) {
    LiveCategory.BuiltIn.ALL -> stringResource(R.string.content_category_all_channels)
    LiveCategory.BuiltIn.FAVORITES -> stringResource(R.string.content_category_favorites)
    LiveCategory.BuiltIn.HISTORY -> stringResource(R.string.content_category_history)
    LiveCategory.BuiltIn.CATCHUP -> stringResource(R.string.content_catchup)
    null -> title.orEmpty()
}

/**
 * The visible rows that are channels, and so can be peeked. Until the pager has loaded, the list's
 * only row is the "no channels here" line at index 0 — peeking it as a channel threw, and the sheet
 * took the app down the moment it opened.
 */
internal fun channelRowsToPeek(visibleIndices: List<Int>, channelCount: Int): List<Int> =
    visibleIndices.filter { it in 0 until channelCount }

/** Its own view-model instance, so the picker's category never becomes the Live tab's. */
private const val PICKER_VM_KEY = "multiview-picker"

/** AerioTV's phone picker height. */
private val PICKER_LIST_HEIGHT = 520.dp
