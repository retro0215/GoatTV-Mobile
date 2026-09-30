package tv.own.owntv.mobile.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.brand.MobileBrand

/** Weather temperatures: one conversion from core's Celsius, and Fahrenheit on every reseller app. */
class WeatherTemperatureTest {

    @Test
    fun celsiusToFahrenheit() {
        assertEquals(32, WeatherTemperature.display(0f, TemperatureUnit.FAHRENHEIT))
        assertEquals(50, WeatherTemperature.display(10f, TemperatureUnit.FAHRENHEIT))
        assertEquals(68, WeatherTemperature.display(20f, TemperatureUnit.FAHRENHEIT))
        assertEquals(86, WeatherTemperature.display(30f, TemperatureUnit.FAHRENHEIT))
        assertEquals(14, WeatherTemperature.display(-10f, TemperatureUnit.FAHRENHEIT))
    }

    @Test
    fun wholeDegreesAreRoundedNotTruncated() {
        // 21.7 °C = 71.06 °F; 22.5 °C = 72.5 °F; -12.4 °C = 9.68 °F.
        assertEquals(71, WeatherTemperature.display(21.7f, TemperatureUnit.FAHRENHEIT))
        assertEquals(73, WeatherTemperature.display(22.5f, TemperatureUnit.FAHRENHEIT))
        assertEquals(10, WeatherTemperature.display(-12.4f, TemperatureUnit.FAHRENHEIT))
        assertEquals(22, WeatherTemperature.display(21.7f, TemperatureUnit.CELSIUS))
    }

    @Test
    fun celsiusIsShownAsIs() {
        assertEquals(20, WeatherTemperature.display(20f, TemperatureUnit.CELSIUS))
    }

    @Test
    fun everyBrandUsesFahrenheit_whateverTheSettingHolds() {
        for (brand in listOf(MobileBrand.GOAT, MobileBrand.ALLACCESS, MobileBrand.FIVESTAR, MobileBrand.SUPREME)) {
            assertEquals(brand.name, TemperatureUnit.FAHRENHEIT, brand.temperatureUnit)
            assertEquals(brand.name, TemperatureUnit.FAHRENHEIT, WeatherTemperature.unitFor(brand, userPrefersFahrenheit = false))
            assertEquals(brand.name, TemperatureUnit.FAHRENHEIT, WeatherTemperature.unitFor(brand, userPrefersFahrenheit = true))
            assertFalse(brand.name, WeatherTemperature.userChoosesUnit(brand))
        }
    }

    @Test
    fun unbrandedFollowsTheUsersSetting() {
        assertEquals(TemperatureUnit.CELSIUS, WeatherTemperature.unitFor(null, userPrefersFahrenheit = false))
        assertEquals(TemperatureUnit.FAHRENHEIT, WeatherTemperature.unitFor(null, userPrefersFahrenheit = true))
        assertTrue(WeatherTemperature.userChoosesUnit(null))
    }

    @Test
    fun noDoubleConversion() {
        // A branded app with the old "Fahrenheit" setting on still converts once: 20 °C is 68 °F,
        // not the 154 °F that converting 68 again would give.
        val unit = WeatherTemperature.unitFor(MobileBrand.GOAT, userPrefersFahrenheit = true)
        assertEquals(68, WeatherTemperature.display(20f, unit))
    }
}
