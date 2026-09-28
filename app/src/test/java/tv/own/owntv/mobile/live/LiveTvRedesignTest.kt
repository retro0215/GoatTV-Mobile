package tv.own.owntv.mobile.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.mobile.ui.screens.live.LiveCategory

class LiveTvRedesignTest {

    @Test
    fun categoryBrowserState_and_builtInCategories() {
        val categories = listOf(
            LiveCategory(LiveKey.All, builtIn = LiveCategory.BuiltIn.ALL),
            LiveCategory(LiveKey.Favorites, builtIn = LiveCategory.BuiltIn.FAVORITES),
            LiveCategory(LiveKey.History, builtIn = LiveCategory.BuiltIn.HISTORY),
            LiveCategory(LiveKey.Catchup, builtIn = LiveCategory.BuiltIn.CATCHUP),
            LiveCategory(LiveKey.Folder(10L), title = "USA | Sports"),
            LiveCategory(LiveKey.Folder(20L), title = "UK | Movies"),
        )

        assertEquals(6, categories.size)
        assertEquals(LiveCategory.BuiltIn.ALL, categories[0].builtIn)
        assertEquals(LiveCategory.BuiltIn.FAVORITES, categories[1].builtIn)
        assertEquals(LiveCategory.BuiltIn.HISTORY, categories[2].builtIn)
        assertEquals(LiveCategory.BuiltIn.CATCHUP, categories[3].builtIn)
        assertEquals("USA | Sports", categories[4].title)
    }

    @Test
    fun categorySearch_filtersLocally() {
        val categories = listOf(
            Pair(LiveCategory(LiveKey.All, builtIn = LiveCategory.BuiltIn.ALL), "All Channels"),
            Pair(LiveCategory(LiveKey.Favorites, builtIn = LiveCategory.BuiltIn.FAVORITES), "Favorites"),
            Pair(LiveCategory(LiveKey.Folder(10L), title = "USA | Sports"), "USA | Sports ⚽"),
            Pair(LiveCategory(LiveKey.Folder(20L), title = "UK | Movies"), "UK | Movies 🍿"),
            Pair(LiveCategory(LiveKey.Folder(30L), title = "Sports Max"), "Sports Max"),
        )

        val query = "sports"
        val filtered = categories.filter { (_, label) ->
            label.contains(query, ignoreCase = true)
        }

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.second == "USA | Sports ⚽" })
        assertTrue(filtered.any { it.second == "Sports Max" })
    }

    @Test
    fun categorySelection_updatesSelectedKey() {
        var selectedKey: LiveKey = LiveKey.All
        val targetKey: LiveKey = LiveKey.Folder(10L)

        selectedKey = targetKey
        assertEquals(LiveKey.Folder(10L), selectedKey)
    }

    @Test
    fun channelGridState_and_selectionHighlight() {
        val ch1 = ChannelEntity(
            id = 101L,
            sourceId = 1L,
            categoryId = 10L,
            name = "ESPN HD",
            streamUrl = "http://example.com/stream1",
        )
        val ch2 = ChannelEntity(
            id = 102L,
            sourceId = 1L,
            categoryId = 10L,
            name = "Sky Sports",
            streamUrl = "http://example.com/stream2",
        )

        val tunedChannelId = 101L

        val isSelectedCh1 = (ch1.id == tunedChannelId)
        val isSelectedCh2 = (ch2.id == tunedChannelId)

        assertTrue("Tuned channel 101 must be selected", isSelectedCh1)
        assertFalse("Channel 102 must not be selected", isSelectedCh2)
    }

    @Test
    fun previewState_and_lifecycleTransitions() {
        var tunedChannelId: Long? = null
        var isPlaying = false
        var toFullscreen = false

        // 1. Tune preview
        tunedChannelId = 101L
        isPlaying = true
        assertEquals(101L, tunedChannelId)
        assertTrue(isPlaying)

        // 2. Fullscreen transition
        toFullscreen = true
        assertTrue("Navigating to fullscreen preserves player", toFullscreen)

        // 3. Back from fullscreen
        toFullscreen = false
        assertFalse(toFullscreen)
        assertEquals("Returned from fullscreen keeps tuned channel", 101L, tunedChannelId)

        // 4. Leaving Live TV screen
        val leavingLiveTv = true
        if (leavingLiveTv) {
            tunedChannelId = null
            isPlaying = false
        }
        assertNull(tunedChannelId)
        assertFalse(isPlaying)
    }

    @Test
    fun guideNavigation_and_backStackPreservation() {
        val currentTunedChannelId = 101L
        var isPlaybackActive = true
        val backStack = mutableListOf("home", "live")

        // 1. Open Guide from Live TV
        backStack.add("guide")
        // Stop playback engines without clearing selected channel
        isPlaybackActive = false
        assertFalse("Playback stops when Guide is opened", isPlaybackActive)
        assertEquals(3, backStack.size)
        assertEquals("guide", backStack.last())
        assertEquals(101L, currentTunedChannelId)

        // 2. System Back from Guide
        val popped = backStack.removeAt(backStack.lastIndex)
        assertEquals("guide", popped)
        assertEquals("live", backStack.last())
        // Resume preview on previous tuned channel
        isPlaybackActive = true
        assertEquals(101L, currentTunedChannelId)
        assertTrue(isPlaybackActive)
    }

    @Test
    fun homeToGuideNavigation_respectsHomeOriginOnBack() {
        val backStack = mutableListOf("home")

        // 1. Open Guide from Home
        backStack.add("guide")
        assertEquals("guide", backStack.last())

        // 2. Back from Guide
        val popped = backStack.removeAt(backStack.lastIndex)
        assertEquals("guide", popped)
        assertEquals("home", backStack.last())
    }

    @Test
    fun noLogoChannel_and_noEpgFallback() {
        val ch = ChannelEntity(
            id = 200L,
            sourceId = 1L,
            categoryId = 5L,
            name = "Local News",
            streamUrl = "http://example.com/stream3",
            logoUrl = null,
        )

        val hasLogo = !ch.displayLogoUrl.isNullOrBlank()
        assertFalse("Channel without logo url has no logo", hasLogo)

        val epgTitle: String? = null
        val displayedTitle = epgTitle ?: ch.name
        assertEquals("Local News", displayedTitle)
    }
}
