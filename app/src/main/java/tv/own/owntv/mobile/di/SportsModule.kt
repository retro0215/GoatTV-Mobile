package tv.own.owntv.mobile.di

import org.koin.dsl.module
import tv.own.owntv.mobile.sports.live.MobileSportsAccessData
import tv.own.owntv.mobile.sports.live.SportsAccessData
import tv.own.owntv.mobile.sports.live.SportsApiClient
import tv.own.owntv.mobile.sports.live.SportsChannelAccess
import tv.own.owntv.mobile.sports.live.SportsLiveStore
import tv.own.owntv.mobile.sports.live.asSportsApi

/**
 * GoatTV Sports: the backend client, the in-memory store and channel access.
 *
 * All singles, created lazily — the first screen that asks — and creating them starts nothing: polling
 * only runs while a screen showing Sports drives [SportsLiveStore.runWhileActive], so the app never
 * polls at launch or in the background. The client reuses the app's shared OkHttpClient (core's), with
 * Sports' own timeouts.
 *
 * Matched channels are reachable ONLY through [SportsChannelAccess]: identity, playlist isolation and
 * the profile's Live TV visibility rules together. There is deliberately no binding for the bare
 * resolver, so nothing can offer a channel the profile is not allowed to see.
 */
val sportsModule = module {
    single { SportsApiClient(sharedClient = get()) }
    single { SportsLiveStore(api = get<SportsApiClient>().asSportsApi()) }
    single<SportsAccessData> {
        MobileSportsAccessData(
            settings = get(),
            sourceDao = get(),
            channelDao = get(),
            categoryDao = get(),
            profileDao = get(),
            customize = get(),
        )
    }
    single { SportsChannelAccess(get()) }
}
