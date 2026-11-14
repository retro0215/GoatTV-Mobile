package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real client end to end, with an OkHttp interceptor standing in for the server: nothing touches
 * the network. Covers what [SportsApiClientInterpretTest] cannot — the URL actually requested, the
 * timeouts, a network failure and a cancelled call.
 */
class SportsApiClientHttpTest {

    private val requested = mutableListOf<String>()

    private fun client(brand: String? = "allaccess", answer: (Interceptor.Chain) -> Response): SportsApiClient {
        val shared = OkHttpClient.Builder()
            .addInterceptor { chain -> requested += chain.request().url.toString(); answer(chain) }
            .build()
        return SportsApiClient(shared, baseUrl = "https://sports.test/", brand = brand)
    }

    private fun Interceptor.Chain.reply(code: Int, body: String, type: String? = "application/json", retryAfter: String? = null): Response =
        Response.Builder()
            .request(request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body(body.toResponseBody(type?.toMediaType()))
            .build()

    private val homeBody = SportsFixtures.home(listOf(SportsFixtures.event("evt_a")), listOf("evt_a"))

    @Test
    fun home_requestsTheBrandedPath_andParses() = runBlocking {
        val r = client { it.reply(200, homeBody) }.home()
        assertEquals("https://sports.test/sports/home?brand=allaccess", requested.single())
        assertEquals(listOf("evt_a"), (r as SportsApiResult.Success).value.events.map { e -> e.id })
    }

    @Test
    fun leagues_areNotBranded() = runBlocking {
        client { it.reply(200, """{"leagues":[{"id":"nfl","name":"NFL","order":1,"sport":"football"}]}""") }.leagues()
        assertEquals("https://sports.test/sports/leagues", requested.single())
    }

    @Test
    fun unbrandedBuild_neverAsksForChannels() = runBlocking {
        client(brand = null) { it.reply(200, homeBody) }.home()
        assertEquals("https://sports.test/sports/home", requested.single())
    }

    @Test
    fun tighterTimeoutsThanTheSharedClient() {
        var seen = 0
        runBlocking {
            client { chain ->
                seen = chain.connectTimeoutMillis()
                assertEquals(10_000, chain.readTimeoutMillis())
                assertEquals(15_000, chain.call().timeout().timeoutNanos() / 1_000_000)
                chain.reply(200, homeBody)
            }.home()
        }
        assertEquals(8_000, seen)
    }

    @Test
    fun rateLimited_withRetryAfter_clampedToTenMinutes() = runBlocking {
        val api = client { it.reply(429, """{"error":"Too many requests."}""", retryAfter = "3600") }
        assertEquals(SportsApiResult.RateLimited(600), api.home())
        val noHeader = client { it.reply(429, "") }
        assertEquals(SportsApiResult.RateLimited(60), noHeader.home())
    }

    @Test
    fun edgeHtmlPage_serverError_malformedJson_andEmptyBody_areTemporary() = runBlocking {
        assertEquals(
            SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER),
            client { it.reply(504, "<html>504 Gateway Time-out</html>", "text/html") }.home(),
        )
        assertEquals(
            SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON),
            client { it.reply(200, "<html>maintenance</html>", "text/html") }.home(),
        )
        assertEquals(
            SportsApiResult.Unavailable(SportsApiResult.Reason.MALFORMED),
            client { it.reply(200, """{"cursor":"c"}""") }.home(),
        )
        assertEquals(
            SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON),
            client { it.reply(200, "", null) }.home(),
        )
    }

    @Test
    fun rejectedCursor_carriesTheServersMessage() = runBlocking {
        val r = client { it.reply(400, """{"error":"since is too old; reload /home.","requestId":"r"}""") }.eventsSince("old")
        assertEquals(SportsApiResult.Rejected(400, "since is too old; reload /home."), r)
        assertTrue(requested.single().contains("since=old&brand=allaccess"))
    }

    @Test
    fun networkFailure_isUnavailable_notAnException() = runBlocking {
        assertEquals(
            SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK),
            client { throw IOException("no route to host") }.home(),
        )
    }

    @Test
    fun cancellingTheCaller_cancelsTheHttpCall() {
        val entered = CountDownLatch(1)
        val sawCancel = AtomicBoolean(false)
        val api = client { chain ->
            entered.countDown()
            // Hold the request open until the call is cancelled (or give up after 5 s).
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
            sawCancel.set(chain.call().isCanceled())
            throw IOException("cancelled")
        }
        runBlocking {
            val pending = async(Dispatchers.Default) { api.home() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            val done = CompletableDeferred<Unit>()
            withTimeout(5_000) {
                while (!sawCancel.get()) kotlinx.coroutines.delay(10)
                done.complete(Unit)
            }
            assertTrue("the OkHttp call was cancelled with the coroutine", sawCancel.get())
            assertTrue(pending.isCancelled)
        }
    }
}
