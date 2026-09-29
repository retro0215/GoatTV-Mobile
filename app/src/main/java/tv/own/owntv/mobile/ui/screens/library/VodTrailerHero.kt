package tv.own.owntv.mobile.ui.screens.library

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileButton
import tv.own.owntv.mobile.ui.components.MobileIcons

private const val HTML_MIME_TYPE = "text/html"
private const val HTML_ENCODING = "utf-8"
private const val BASE_URL = "https://www.youtube-nocookie.com"
private const val BLANK_URL = "about:blank"
private const val JS_BRIDGE = "TrailerBridge"
private const val JS_MUTE = "window.mutePlayer && mutePlayer();"
private const val JS_UNMUTE = "window.unmutePlayer && unmutePlayer();"
private const val JS_PAUSE = "window.pausePlayer && pausePlayer();"
private const val JS_RESUME = "window.resumePlayer && resumePlayer();"
private const val JS_PLAY_WITH_SOUND = "window.playWithSound && playWithSound();"

/** How long a trailer may sit un-started after `onReady` before autoplay counts as refused. */
internal const val AUTOPLAY_CHECK_MS = 3_000

/**
 * The top of a film's or a show's page: its trailer when it has one, its artwork when it does not.
 *
 * The trailer plays **with sound** as soon as it is ready — the page is opened to see the film, and a
 * silent trailer is the thing nobody asked for. If the WebView refuses to start it with sound, the
 * hero does not fall back to playing muted; it shows a Play Trailer button instead, and the tap starts
 * it with sound. A trailer YouTube will not embed (error 2/5/100/101/150/153) gives way to artwork.
 *
 * Playing the film, going Back, or leaving the app stops the trailer: the first two remove the
 * WebView outright, the last pauses it until the app is in front again.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VodTrailerHero(
    trailerKey: String?,
    backdropUrl: String?,
    posterUrl: String?,
    isPlayingVod: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cleanTrailerKey = remember(trailerKey) { extractYouTubeVideoId(trailerKey) }
    var isMuted by rememberSaveable { mutableStateOf(false) }
    var hasError by remember(cleanTrailerKey) { mutableStateOf(false) }
    var autoplayBlocked by remember(cleanTrailerKey) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val showTrailer = shouldShowTrailer(cleanTrailerKey, hasError, isPlayingVod)

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (showTrailer) key(cleanTrailerKey) {
            // Keyed so a different trailer — the next title in the tablet's two panes — gets a
            // WebView of its own rather than the old one, destroyed, still on screen.
            val videoId = cleanTrailerKey.orEmpty()
            // Built once per trailer: the mute state it starts in is the one at load time, and the
            // button changes it through JavaScript after that without reloading the page.
            val htmlContent = remember(videoId) { trailerPlayerHtml(videoId, startMuted = isMuted) }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        addJavascriptInterface(
                            TrailerBridge(
                                onPlaying = { autoplayBlocked = false },
                                onBlocked = { autoplayBlocked = true },
                                onError = { hasError = true },
                            ),
                            JS_BRIDGE,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                // Only the page itself failing means no player; a stray sub-resource does not.
                                if (request?.isForMainFrame == true) hasError = true
                            }
                        }
                        loadDataWithBaseURL(BASE_URL, htmlContent, HTML_MIME_TYPE, HTML_ENCODING, null)
                        webViewRef = this
                    }
                },
                update = { webView -> webViewRef = webView },
                modifier = Modifier.fillMaxSize(),
            )

            // Home or the recents screen: the page is still composed, so the trailer has to be told.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, videoId) {
                val observer = LifecycleEventObserver { _, event ->
                    val web = webViewRef ?: return@LifecycleEventObserver
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            web.evaluateJavascript(JS_PAUSE, null)
                            web.onPause()
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            web.onResume()
                            web.evaluateJavascript(JS_RESUME, null)
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // Leaving the composition — Play, Back, another title — ends the trailer and its audio.
            DisposableEffect(videoId) {
                onDispose {
                    webViewRef?.run {
                        stopLoading()
                        loadUrl(BLANK_URL)
                        destroy()
                    }
                    webViewRef = null
                }
            }

            if (autoplayBlocked) {
                AsyncImage(
                    model = backdropUrl ?: posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                MobileButton(
                    text = stringResource(R.string.content_play_trailer),
                    onClick = {
                        isMuted = false
                        autoplayBlocked = false
                        webViewRef?.evaluateJavascript(JS_PLAY_WITH_SOUND, null)
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            webViewRef?.evaluateJavascript(if (isMuted) JS_MUTE else JS_UNMUTE, null)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isMuted) MobileIcons.VolumeOff else MobileIcons.VolumeUp,
                            contentDescription = stringResource(
                                if (isMuted) R.string.content_trailer_unmute else R.string.content_trailer_mute,
                            ),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { context.playTrailer(videoId) },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    ) {
                        Icon(
                            imageVector = MobileIcons.OpenInFull,
                            contentDescription = stringResource(R.string.content_trailer_fullscreen),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        } else {
            // Artwork Fallback (Backdrop -> Poster -> Generic)
            AsyncImage(
                model = backdropUrl ?: posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The page's way back to Compose. JavaScript calls arrive on a WebView thread, so each one is handed
 * to the main thread before it touches state.
 */
private class TrailerBridge(
    private val onPlaying: () -> Unit,
    private val onBlocked: () -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun playing() {
        main.post(onPlaying)
    }

    @JavascriptInterface
    fun blocked() {
        main.post(onBlocked)
    }

    @JavascriptInterface
    fun error(code: Int) {
        main.post { onError(code) }
    }
}

/**
 * The player page: the YouTube IFrame API, reporting back through [JS_BRIDGE].
 *
 * It asks to play unmuted unless [startMuted]. `playsinline` keeps the trailer in the hero rather than
 * taking over the screen, and if the video has not started [AUTOPLAY_CHECK_MS] after it was asked to,
 * the page reports it blocked instead of retrying muted.
 */
internal fun trailerPlayerHtml(videoId: String, startMuted: Boolean): String = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    html, body { margin:0; padding:0; background-color:#000; overflow:hidden; }
    #player { position:fixed; top:0; left:0; border:0; display:block; }
  </style>
</head>
<body>
  <div id="player"></div>
  <script>
    var START_MUTED = $startMuted;
    var player;
    var started = false;
    var tag = document.createElement('script');
    tag.src = "https://www.youtube.com/iframe_api";
    document.head.appendChild(tag);

    // Sized in pixels, never by CSS: inside the app's scrolling list the WebView starts out 0 px
    // tall, and percentage and vh heights stay resolved against that — YouTube's iframe came out
    // 0 px high, so the trailer was heard, never seen, and YouTube paused it as too small to watch.
    function fitPlayer() {
      var w = window.innerWidth, h = window.innerHeight;
      if (player && player.setSize && w > 0 && h > 0) player.setSize(w, h);
    }
    window.addEventListener('resize', fitPlayer);

    function onYouTubeIframeAPIReady() {
      player = new YT.Player('player', {
        height: String(window.innerHeight || 1),
        width: String(window.innerWidth || 1),
        videoId: '$videoId',
        playerVars: {
          'autoplay': 1,
          'mute': START_MUTED ? 1 : 0,
          'controls': 0,
          'playsinline': 1,
          'rel': 0,
          'modestbranding': 1,
          'enablejsapi': 1,
          'origin': '$BASE_URL'
        },
        events: {
          'onReady': onPlayerReady,
          'onStateChange': onPlayerStateChange,
          'onError': onPlayerError
        }
      });
    }
    function onPlayerReady(event) {
      fitPlayer();
      if (START_MUTED) { event.target.mute(); } else { event.target.unMute(); }
      event.target.playVideo();
      setTimeout(function() {
        var state = player.getPlayerState();
        if (!started && state !== 1 && state !== 3) { $JS_BRIDGE.blocked(); }
      }, $AUTOPLAY_CHECK_MS);
    }
    function onPlayerStateChange(event) {
      if (event.data === 1) { started = true; $JS_BRIDGE.playing(); }
    }
    function onPlayerError(event) {
      $JS_BRIDGE.error(event.data);
    }
    function mutePlayer() { if (player && player.mute) player.mute(); }
    function unmutePlayer() { if (player && player.unMute) player.unMute(); }
    function pausePlayer() { if (player && player.pauseVideo) player.pauseVideo(); }
    function resumePlayer() { if (player && player.playVideo && started) player.playVideo(); }
    function playWithSound() { if (player) { player.unMute(); player.playVideo(); } }
  </script>
</body>
</html>
""".trimIndent()

/**
 * Whether the hero is the trailer rather than artwork. The film or an episode starting is what turns
 * it off: the WebView leaves the composition with it, so the trailer's audio ends before the
 * playback's begins.
 */
internal fun shouldShowTrailer(videoId: String?, hasError: Boolean, isPlayingVod: Boolean): Boolean =
    !videoId.isNullOrBlank() && !hasError && !isPlayingVod

/** Extracts clean 11-character YouTube Video ID from raw ID or YouTube URL string */
internal fun extractYouTubeVideoId(input: String?): String? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()
    if (trimmed.length == 11 && trimmed.matches(Regex("[a-zA-Z0-9_-]{11}"))) {
        return trimmed
    }
    val regex = Regex("""(?:youtube\.com/(?:watch\?.*v=|embed/|v/)|youtu\.be/)([a-zA-Z0-9_-]{11})""")
    val match = regex.find(trimmed)
    if (match != null && match.groupValues.size > 1) {
        return match.groupValues[1]
    }
    return null
}
