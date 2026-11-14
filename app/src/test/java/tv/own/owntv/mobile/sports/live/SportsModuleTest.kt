package tv.own.owntv.mobile.sports.live

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import tv.own.owntv.mobile.di.sportsModule
import java.util.concurrent.atomic.AtomicInteger

/** The Sports module resolves from the app's shared OkHttpClient, and resolving it fetches nothing. */
class SportsModuleTest {

    @Test
    fun resolvesSingles_withTheSharedClient_andStartsNoRequest() {
        val requests = AtomicInteger()
        val shared = OkHttpClient.Builder()
            .addInterceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) }
            .build()
        val koin = koinApplication { modules(module { single { shared } }, sportsModule) }.koin
        val store = koin.get<SportsLiveStore>()
        assertSame("one store for the app", store, koin.get<SportsLiveStore>())
        assertSame(koin.get<SportsApiClient>(), koin.get<SportsApiClient>())
        assertEquals("nothing polls until a screen asks", SportsLiveState.Loading, store.state.value)
        assertEquals(0, requests.get())
    }
}
