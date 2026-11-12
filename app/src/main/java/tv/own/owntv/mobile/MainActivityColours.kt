package tv.own.owntv.mobile

// One launcher activity per icon colour, so each can carry its own icon and launch-screen theme (see the
// manifest and core's AppIconSwitcher). No code of their own: MainActivity is the app.
class MainActivitySunflower : MainActivity()
class MainActivityCobalt : MainActivity()
class MainActivityTomato : MainActivity()
class MainActivityBoard : MainActivity()
class MainActivityPetrol : MainActivity()
class MainActivityOlive : MainActivity()
class MainActivityOliveCream : MainActivity()

// Core 1.0.67 added AppIcon.PIXEL, and AppIconSwitcher enables or disables a component for every icon.
// It is not offered in the picker; it exists so the switcher never names a missing component.
class MainActivityPixel : MainActivity()
