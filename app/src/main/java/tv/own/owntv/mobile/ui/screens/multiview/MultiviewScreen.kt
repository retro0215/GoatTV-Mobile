package tv.own.owntv.mobile.ui.screens.multiview

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.displayText
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileIcons
import tv.own.owntv.mobile.ui.components.MobileListRow
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.player.describe

/**
 * The phone's Multiview: up to five live channels, one of them heard — AerioTV's grid, touch edition.
 *
 * | Gesture | Does |
 * | --- | --- |
 * | tap a tile | give it the sound (a lone tile: show or hide the controls) |
 * | double-tap | that tile fills the grid; again to go back |
 * | long-press, then drag | move the tile to where it is dropped |
 * | long-press, release | the tile menu |
 * | tap the background | show or hide the controls |
 *
 * Back steps out of move mode, then out of full-screen-in-grid, then leaves the screen with the grid
 * kept for next time; only the X closes it. Tiles are drawn in player-slot order and placed by
 * offset, so moving one on screen never recreates its picture or touches its stream.
 */
@UnstableApi
@Composable
fun MultiviewScreen(
    session: MultiviewSession,
    prefs: MultiviewPreferences,
    /** Back with the grid kept. */
    onLeave: () -> Unit,
    /** The X: the grid is already closed when this runs. */
    onClosed: () -> Unit,
    /** A tile opened in the regular full-screen player. The grid is already put away. */
    onOpenInPlayer: (ChannelEntity) -> Unit,
    openPickerOnEntry: Boolean = false,
) {
    val model by session.model.collectAsStateWithLifecycle()
    val problems by session.problems.collectAsStateWithLifecycle()
    val layoutMode by prefs.layoutMode.collectAsStateWithLifecycle()
    val style by prefs.audioFocusStyle.collectAsStateWithLifecycle()
    val tilePadding by prefs.tilePadding.collectAsStateWithLifecycle()
    val rounded by prefs.roundedCorners.collectAsStateWithLifecycle()

    // Playing only while this screen is in front: Home, a tile opened full screen and leaving with
    // Back all stop every tile and give its connection back; coming back tunes the same grid again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.start()
                Lifecycle.Event.ON_STOP -> session.suspend()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.suspend()
        }
    }
    // Five pictures and nobody touching the screen: the phone's own timeout would otherwise end it.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(0L) }
    var relocatingSlot by remember { mutableStateOf<Int?>(null) }
    var menuSlot by remember { mutableStateOf<Int?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(openPickerOnEntry) }
    var swapTargetSlot by remember { mutableStateOf<Int?>(null) }
    var pendingWarnedAdd by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun poke() {
        chromeVisible = true
        lastInteraction = System.nanoTime()
    }

    // AerioTV's 4 s auto-hide, standing down while a tile is being moved so its hint cannot vanish.
    LaunchedEffect(chromeVisible, lastInteraction, relocatingSlot) {
        if (chromeVisible && relocatingSlot == null) {
            delay(CHROME_HIDE_MS)
            chromeVisible = false
        }
    }
    // The accent outline appears when the sound moves and fades after five seconds.
    var focusFaded by remember { mutableStateOf(false) }
    LaunchedEffect(model.audioSlot) {
        focusFaded = false
        delay(ACCENT_FADE_MS)
        focusFaded = true
    }
    // A tile that left (removed, or taken by a profile change) cannot stay picked up or menu'd.
    LaunchedEffect(model.tiles) {
        if (relocatingSlot != null && model.tileIn(relocatingSlot!!) == null) relocatingSlot = null
        if (menuSlot != null && model.tileIn(menuSlot!!) == null) menuSlot = null
    }

    BackHandler {
        when {
            relocatingSlot != null -> relocatingSlot = null
            model.gridFullscreenSlot != null -> session.setGridFullscreen(null)
            else -> onLeave()
        }
    }

    // Every add goes through here: the fifth tile asks first, exactly once per two hours.
    val gatedAdd: (() -> Unit) -> Unit = { commit ->
        if (prefs.shouldWarnBeforeAdding(session.model.value.size)) pendingWarnedAdd = commit else commit()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (chromeVisible) chromeVisible = false else poke()
            },
    ) {
        if (model.isEmpty) {
            EmptyState(onAdd = { pickerOpen = true })
        } else {
            TileGrid(
                session = session,
                model = model,
                problems = problems,
                layoutMode = layoutMode,
                style = style,
                padded = tilePadding,
                rounded = rounded,
                chromeVisible = chromeVisible,
                focusFaded = focusFaded,
                relocatingSlot = relocatingSlot,
                onTap = { slot ->
                    val moving = relocatingSlot
                    when {
                        moving != null && moving != slot -> {
                            session.swap(model.displayIndexOf(moving), model.displayIndexOf(slot))
                            relocatingSlot = null
                        }
                        moving == slot -> relocatingSlot = null
                        model.size == 1 -> if (chromeVisible) chromeVisible = false else poke()
                        else -> session.setAudio(slot)
                    }
                },
                onDoubleTap = { slot ->
                    if (model.size > 1) {
                        session.setGridFullscreen(if (model.gridFullscreenSlot == slot) null else slot)
                        relocatingSlot = null
                    }
                },
                onMenu = { slot -> if (relocatingSlot == null) menuSlot = slot },
                onDragStart = { slot -> if (model.size > 1) relocatingSlot = slot },
                onDrop = { from, to ->
                    session.move(from, to)
                    relocatingSlot = null
                },
            )
        }

        Controls(
            visible = chromeVisible || model.isEmpty,
            count = model.size,
            relocating = relocatingSlot != null,
            gridFullscreen = model.gridFullscreenSlot != null,
            canAdd = !model.isFull,
            showLayoutButton = LocalConfiguration.current.screenWidthDp >= 600 && model.size > 1,
            onClose = {
                when {
                    relocatingSlot != null -> relocatingSlot = null
                    model.gridFullscreenSlot != null -> session.setGridFullscreen(null)
                    else -> {
                        session.close()
                        onClosed()
                    }
                }
            },
            onAdd = { pickerOpen = true },
            onLayout = { menuSlot = model.audioSlot ?: model.tiles.firstOrNull()?.slot },
        )
    }

    menuSlot?.let { slot ->
        val tile = model.tileIn(slot) ?: return@let
        TileMenu(
            tile = tile,
            model = model,
            layoutMode = layoutMode,
            onDismiss = { menuSlot = null },
            onAdd = { menuSlot = null; pickerOpen = true },
            onSwap = { menuSlot = null; swapTargetSlot = slot; pickerOpen = true },
            onAudio = { menuSlot = null; session.setAudio(slot) },
            onGridFullscreen = { menuSlot = null; session.setGridFullscreen(slot); relocatingSlot = null },
            onOpenInPlayer = {
                menuSlot = null
                (tile.content as? MultiviewContent.Live)?.channel?.let(onOpenInPlayer)
            },
            onSpotlight = {
                menuSlot = null
                session.setSpotlight(if (model.spotlightSlot == slot) null else slot)
            },
            onLayout = { mode ->
                menuSlot = null
                prefs.setLayoutMode(mode)
                // Picking a shape ends Spotlight, or the hero would override the shape just chosen.
                session.setSpotlight(null)
            },
            onMove = { menuSlot = null; relocatingSlot = slot; poke() },
            onRemove = {
                menuSlot = null
                val last = model.size <= 1
                session.remove(slot)
                if (last) onLeave()
            },
        )
    }

    if (pickerOpen) {
        AddToMultiviewSheet(
            session = session,
            swapTargetSlot = swapTargetSlot,
            gatedAdd = gatedAdd,
            onDismiss = {
                pickerOpen = false
                swapTargetSlot = null
            },
        )
    }

    pendingWarnedAdd?.let { commit ->
        PerformanceWarning(
            onContinue = { pendingWarnedAdd = null; commit() },
            onDontShowAgain = { prefs.suppressWarning(); pendingWarnedAdd = null; commit() },
            onCancel = { pendingWarnedAdd = null },
        )
    }
}

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileGrid(
    session: MultiviewSession,
    model: MultiviewModel,
    problems: Map<Int, TileProblem>,
    layoutMode: MultiviewLayoutMode,
    style: AudioFocusStyle,
    padded: Boolean,
    rounded: Boolean,
    chromeVisible: Boolean,
    focusFaded: Boolean,
    relocatingSlot: Int?,
    onTap: (Int) -> Unit,
    onDoubleTap: (Int) -> Unit,
    onMenu: (Int) -> Unit,
    onDragStart: (Int) -> Unit,
    onDrop: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    var dragSlot by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val count = model.size
        val spotlightIndex = model.spotlightSlot?.let(model::displayIndexOf)?.takeIf { it >= 0 }
        val dpRects = MultiviewGridMath.resolvedRects(layoutMode, count, spotlightIndex, maxWidth.value, maxHeight.value)
        val pxRects = MultiviewGridMath.resolvedRects(
            layoutMode, count, spotlightIndex,
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
        )
        val hoverIndex = dragSlot?.let { MultiviewGridMath.indexAt(dragPos, pxRects) }
        val fullSlot = model.gridFullscreenSlot
        val gap = if (padded) TILE_GAP else 0.dp

        // Composed in slot order, never display order: a tile's picture belongs to its slot, and
        // placing it by offset is what lets it move without its TextureView being rebuilt.
        model.tiles.sortedBy { it.slot }.forEach { tile ->
            val slot = tile.slot
            val index = model.displayIndexOf(slot)
            // No early return inside `key`: a return out of that inline composable lambda compiles to
            // a method D8 cannot represent.
            val r = dpRects.getOrNull(index)
            if (r != null) key(slot) {
                val isFull = fullSlot == slot
                val x = when {
                    isFull -> 0f
                    fullSlot != null -> maxWidth.value + r.left // parked just off the right edge
                    else -> r.left
                }
                val y = if (isFull) 0f else r.top
                val w = if (isFull) maxWidth.value else r.width
                val h = if (isFull) maxHeight.value else r.height
                Box(
                    Modifier
                        .offset(x.dp, y.dp)
                        .size(w.dp, h.dp)
                        .zIndex(if (isFull) 1f else 0f)
                        .padding(gap)
                        .then(
                            if (fullSlot == null) {
                                Modifier.pointerInput(slot, index, count, pxRects.getOrNull(index)) {
                                    // A long press that then drags is a move; released in place, the
                                    // menu. Armed only past twice the touch slop — a finger resting on
                                    // glass still moves a little.
                                    val slop = viewConfiguration.touchSlop * 2f
                                    var moved = false
                                    var travelled = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            moved = false
                                            travelled = 0f
                                            dragSlot = slot
                                            val pr = pxRects.getOrNull(index)
                                            dragPos = Offset((pr?.left ?: 0f) + at.x, (pr?.top ?: 0f) + at.y)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            travelled += amount.getDistance()
                                            if (!moved && travelled > slop) {
                                                moved = true
                                                onDragStart(slot)
                                            }
                                            dragPos += amount
                                        },
                                        onDragEnd = {
                                            if (!moved) {
                                                onMenu(slot)
                                            } else {
                                                val to = MultiviewGridMath.indexAt(dragPos, pxRects)
                                                val from = model.displayIndexOf(slot)
                                                if (to != from) onDrop(from, to)
                                            }
                                            dragSlot = null
                                        },
                                        onDragCancel = { dragSlot = null },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Tile(
                        session = session,
                        tile = tile,
                        problem = problems[slot],
                        audible = model.audioSlot == slot,
                        style = style,
                        rounded = rounded && !isFull,
                        chromeVisible = chromeVisible,
                        focusFaded = focusFaded,
                        relocating = relocatingSlot == slot || dragSlot == slot,
                        dropTarget = hoverIndex == index && dragSlot != slot,
                        onTap = { onTap(slot) },
                        onDoubleTap = { onDoubleTap(slot) },
                    )
                }
            }
        }
    }
}

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    session: MultiviewSession,
    tile: MultiviewTile,
    problem: TileProblem?,
    audible: Boolean,
    style: AudioFocusStyle,
    rounded: Boolean,
    chromeVisible: Boolean,
    focusFaded: Boolean,
    relocating: Boolean,
    dropTarget: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
) {
    val shape = RoundedCornerShape(if (rounded) 8.dp else 0.dp)
    val borderColor by animateColorAsState(
        targetValue = when {
            dropTarget -> MaterialTheme.colorScheme.tertiary
            relocating -> MaterialTheme.colorScheme.primary
            audible && style == AudioFocusStyle.GrayOutline -> Color.White.copy(alpha = 0.5f)
            audible && style == AudioFocusStyle.AccentFading && !focusFaded -> MaterialTheme.colorScheme.primary
            else -> Color.White.copy(alpha = 0.08f)
        },
        animationSpec = tween(350),
        label = "tileBorderColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            dropTarget -> 4.dp
            relocating -> 3.dp
            audible && (style == AudioFocusStyle.GrayOutline || (style == AudioFocusStyle.AccentFading && !focusFaded)) -> 2.dp
            else -> 1.dp
        },
        animationSpec = tween(350),
        label = "tileBorderWidth",
    )
    val channel = (tile.content as? MultiviewContent.Live)?.channel
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .background(Color.Black)
            .border(borderWidth, borderColor, shape)
            .combinedClickable(onClick = onTap, onDoubleClick = onDoubleTap),
    ) {
        if (problem != null) {
            ProblemCard(
                text = when (problem) {
                    is TileProblem.Refused -> problem.grant.displayText(LocalContext.current.resources)
                    TileProblem.DeviceLimit -> stringResource(R.string.multiview_decoder_exhausted_mobile)
                },
                onRetry = { session.retry(tile.slot) },
            )
        } else if (channel != null) {
            EngineTile(session = session, slot = tile.slot, channelId = channel.id)
        }
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Text(
                text = tile.content.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (audible && style == AudioFocusStyle.CenterIcon && chromeVisible) {
            Icon(
                imageVector = MobileIcons.VolumeUp,
                contentDescription = stringResource(R.string.multiview_audio_tile),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(48.dp),
            )
        }
    }
}

/**
 * The picture, or the engine's own account of why there is none.
 *
 * A tile whose picture stops while the rest play on, with no error from the engine, has hit the
 * phone's limit — decoders, bandwidth or video planes — and says so after [STREAM_LOST_GRACE_MS]
 * rather than the eighty seconds the engine's reconnect ladder takes to give up.
 */
@UnstableApi
@Composable
private fun BoxScope.EngineTile(session: MultiviewSession, slot: Int, channelId: Long) {
    val engine = session.engineFor(slot)
    val engineState by engine.state.collectAsStateWithLifecycle()
    val failure by engine.error.collectAsStateWithLifecycle()
    val running by session.running.collectAsStateWithLifecycle()
    LaunchedEffect(slot, channelId, running) {
        if (!running) return@LaunchedEffect
        engine.isPlaying.collectLatest { playing ->
            if (playing) return@collectLatest
            delay(STREAM_LOST_GRACE_MS)
            if (engine.error.value != null) return@collectLatest
            if (!engine.isPlaying.value && session.running.value) session.markDeviceLimit(slot)
        }
    }
    LaunchedEffect(engineState, slot) {
        if (engineState == LivePreviewEngine.State.ERROR) session.onTileFailed(slot)
    }
    if (engineState == LivePreviewEngine.State.ERROR) {
        ProblemCard(text = tileFailureText(failure), onRetry = { session.retry(slot) })
    } else {
        TileSurface(engine = engine, modifier = Modifier.fillMaxSize())
    }
}

/**
 * One tile's picture, through a [TextureView].
 *
 * Not a SurfaceView: each SurfaceView asks the display hardware for a video plane of its own, a
 * phone has very few, and one that gets none shows nothing while its sound plays on. A TextureView
 * is drawn by the GPU like any other view, so five can play at once. Full screen keeps its
 * SurfaceView. Keyed on the engine's surface generation, which a released 4K decoder bumps.
 */
@UnstableApi
@Composable
private fun TileSurface(engine: LivePreviewEngine, modifier: Modifier = Modifier) {
    val surfaceGeneration by engine.surfaceGeneration.collectAsStateWithLifecycle()
    Box(modifier.background(Color.Black)) {
        key(surfaceGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        // The Surface is ours to make and release: leaking one keeps a decoder alive.
                        var surface: Surface? = null
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture).also { engine.setSurface(it) }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                surface?.let { engine.detachSurface(it); it.release() }
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun BoxScope.ProblemCard(text: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.multiview_playback_problem),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
    }
}

@Composable
private fun BoxScope.EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = MobileIcons.GridView,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.multiview_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        MobileButton(text = stringResource(R.string.multiview_add_channel), onClick = onAdd)
    }
}

@Composable
private fun BoxScope.Controls(
    visible: Boolean,
    count: Int,
    relocating: Boolean,
    gridFullscreen: Boolean,
    canAdd: Boolean,
    showLayoutButton: Boolean,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onLayout: () -> Unit,
) {
    if (!visible) return
    RoundButton(
        icon = MobileIcons.Close,
        description = stringResource(R.string.multiview_close),
        onClick = onClose,
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 14.dp),
    )
    val highlighted = relocating || gridFullscreen
    Text(
        text = when {
            relocating -> stringResource(R.string.multiview_tap_to_swap)
            gridFullscreen -> stringResource(R.string.multiview_double_tap_exit)
            else -> stringResource(R.string.multiview_count, count, MultiviewModel.MAX_TILES)
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 18.dp, top = 18.dp),
    )
    if (!relocating && !gridFullscreen && canAdd && count > 0) {
        RoundButton(
            icon = MobileIcons.Add,
            description = stringResource(R.string.multiview_add_channel),
            onClick = onAdd,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 14.dp, top = 44.dp),
        )
    }
    if (showLayoutButton && !relocating && !gridFullscreen) {
        RoundButton(
            icon = MobileIcons.GridView,
            description = stringResource(R.string.multiview_grid_layout),
            onClick = onLayout,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 14.dp, top = 94.dp),
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = description, tint = Color.White)
        }
    }
}

/**
 * Long-press on a tile: AerioTV's tile menu, less what a live-only phone grid has no use for
 * (subtitle and audio tracks, the scrubber). Actions that need a second tile only appear with one.
 */
@Composable
private fun TileMenu(
    tile: MultiviewTile,
    model: MultiviewModel,
    layoutMode: MultiviewLayoutMode,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onSwap: () -> Unit,
    onAudio: () -> Unit,
    onGridFullscreen: () -> Unit,
    onOpenInPlayer: () -> Unit,
    onSpotlight: () -> Unit,
    onLayout: (MultiviewLayoutMode) -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    val several = model.size > 1
    MobileBottomSheet(onDismissRequest = onDismiss, title = tile.content.title) {
        if (!model.isFull) MenuRow(stringResource(R.string.multiview_add_channel), MobileIcons.Add, onAdd)
        MenuRow(stringResource(R.string.multiview_tile_change_channel), MobileIcons.SwapHoriz, onSwap)
        if (model.audioSlot != tile.slot) MenuRow(stringResource(R.string.multiview_make_audio), MobileIcons.VolumeUp, onAudio)
        if (several) MenuRow(stringResource(R.string.multiview_fullscreen_in_grid), MobileIcons.OpenInFull, onGridFullscreen)
        MenuRow(stringResource(R.string.multiview_tile_fullscreen), MobileIcons.OpenInNew, onOpenInPlayer)
        if (several) {
            MenuRow(
                stringResource(
                    if (model.spotlightSlot == tile.slot) R.string.multiview_spotlight_remove else R.string.multiview_spotlight,
                ),
                MobileIcons.AspectRatio,
                onSpotlight,
            )
        }
        MultiviewLayoutMode.available(model.size).forEach { mode ->
            MenuRow(
                label = stringResource(
                    when (mode) {
                        MultiviewLayoutMode.Auto -> R.string.multiview_layout_default
                        MultiviewLayoutMode.EvenGrid -> R.string.multiview_layout_even
                        MultiviewLayoutMode.Stacked -> R.string.multiview_layout_stacked
                    },
                ),
                icon = if (mode == layoutMode && model.spotlightSlot == null) MobileIcons.Check else MobileIcons.GridView,
                onClick = { onLayout(mode) },
            )
        }
        if (several) MenuRow(stringResource(R.string.multiview_move_tile), MobileIcons.SwapVert, onMove)
        MenuRow(stringResource(R.string.multiview_tile_remove), MobileIcons.Close, onRemove)
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    MobileListRow(
        title = label,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun PerformanceWarning(onContinue: () -> Unit, onDontShowAgain: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.multiview_warning_title)) },
        text = { Text(stringResource(R.string.multiview_warning_body)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.multiview_warning_continue), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDontShowAgain) { Text(stringResource(R.string.multiview_warning_dont_show)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** The engine's own diagnosis, or a plain fallback. Never a guessed number of provider connections. */
@Composable
private fun tileFailureText(failure: PlaybackFailure?): String = when {
    failure == PlaybackFailure.DecoderExhausted -> stringResource(R.string.multiview_decoder_exhausted_mobile)
    failure != null -> {
        LocalConfiguration.current // a language change recomposes this, as stringResource would
        val resources = LocalContext.current.resources
        failure.describe { id, args -> resources.getString(id, *args.toTypedArray()) }
    }
    else -> stringResource(R.string.player_error_channel)
}

/** AerioTV's controls timeout. */
private const val CHROME_HIDE_MS = 4_000L

/** AerioTV's accent-outline fade. */
private const val ACCENT_FADE_MS = 5_000L

/**
 * How long a tile may sit stopped before the grid calls it lost: longer than the slowest real open
 * measured, far shorter than the engine's eight-attempt ladder.
 */
private const val STREAM_LOST_GRACE_MS = 12_000L

/** AerioTV's tile padding. */
private val TILE_GAP = 4.dp
