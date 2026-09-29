package tv.own.owntv.mobile.ui.screens.library

import android.annotation.SuppressLint
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import tv.own.owntv.mobile.ui.components.MobileIcons

private const val HTML_MIME_TYPE = "text/html"
private const val HTML_ENCODING = "utf-8"
private const val BASE_URL = "https://www.youtube-nocookie.com"
private const val BLANK_URL = "about:blank"
private const val JS_MUTE = "document.getElementById('player').contentWindow.postMessage('{\"event\":\"command\",\"func\":\"mute\",\"args\":\"\"}', '*');"
private const val JS_UNMUTE = "document.getElementById('player').contentWindow.postMessage('{\"event\":\"command\",\"func\":\"unMute\",\"args\":\"\"}', '*');"

/**
 * Shared VOD Trailer Hero component for Movie and Series Details.
 *
 * Autoplays YouTube trailer muted in a 16:9 hero container when a valid trailerKey exists.
 * Provides controls for Mute/Unmute and Fullscreen.
 * Fallbacks gracefully to backdrop/poster artwork when no trailer is available or on playback error.
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
    var isMuted by rememberSaveable { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val showTrailer = !trailerKey.isNullOrBlank() && !hasError && !isPlayingVod

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (showTrailer) {
            val htmlContent = remember(trailerKey) {
                """
                <!DOCTYPE html>
                <html>
                <head>
                <style>
                  body, html { margin:0; padding:0; width:100%; height:100%; background-color:#000; overflow:hidden; }
                  iframe { width:100%; height:100%; border:none; }
                </style>
                </head>
                <body>
                  <iframe id="player"
                    src="https://www.youtube-nocookie.com/embed/$trailerKey?enablejsapi=1&autoplay=1&mute=1&playsinline=1&controls=0&rel=0&modestbranding=1"
                    allow="autoplay; encrypted-media"
                    allowfullscreen>
                  </iframe>
                </body>
                </html>
                """.trimIndent()
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        @Suppress("DEPRECATION")
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?,
                            ) {
                                hasError = true
                            }
                        }
                        loadDataWithBaseURL(BASE_URL, htmlContent, HTML_MIME_TYPE, HTML_ENCODING, null)
                        webViewRef = this
                    }
                },
                update = { webView ->
                    webViewRef = webView
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Lifecycle cleanup for WebView
            DisposableEffect(trailerKey) {
                onDispose {
                    webViewRef?.run {
                        stopLoading()
                        loadUrl(BLANK_URL)
                        destroy()
                    }
                    webViewRef = null
                }
            }

            // Controls Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val newMute = !isMuted
                            isMuted = newMute
                            val jsCmd = if (newMute) JS_MUTE else JS_UNMUTE
                            webViewRef?.evaluateJavascript(jsCmd, null)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isMuted) MobileIcons.VolumeOff else MobileIcons.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            trailerKey?.let { context.playTrailer(it) }
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    ) {
                        Icon(
                            imageVector = MobileIcons.OpenInFull,
                            contentDescription = null,
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
