package tv.own.owntv.mobile.di

import org.koin.dsl.module
import tv.own.owntv.mobile.sports.live.MobileSportsChannelLookup
import tv.own.owntv.mobile.sports.live.SportsApiClient
import tv.own.owntv.mobile.sports.live.SportsChannelLookup
import tv.own.owntv.mobile.sports.live.SportsChannelResolver
import tv.own.owntv.mobile.sports.live.SportsLiveStore
import tv.own.owntv.mobile.sports.live.asSportsApi

/**
 * GoatTV Sports: the backend client and the in-memory store.
 *
 * Both are singles so the last slate outlives the screen, as on the TV. They are created lazily — the
 * first screen that asks for the store — and creating them starts nothing: polling only runs while a
 * screen showing Sports drives [SportsLiveStore.runWhileActive], so the app never polls at launch or in
 * the background. The client reuses the app's shared OkHttpClient (core's), with Sports' own timeouts.
 */
val sportsModule = module {
    single { SportsApiClient(sharedClient = get()) }
    single { SportsLiveStore(api = get<SportsApiClient>().asSportsApi()) }
    // Channel identity and playlist isolation (Phase 2A). Nothing customer-facing resolves through it
    // until the adult and hidden-channel rules sit on top (Phase 2B).
    single<SportsChannelLookup> { MobileSportsChannelLookup(settings = get(), sourceDao = get(), channelDao = get()) }
    single { SportsChannelResolver(get()) }
}
