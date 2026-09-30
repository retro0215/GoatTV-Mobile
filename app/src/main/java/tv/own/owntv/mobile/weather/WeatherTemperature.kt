package tv.own.owntv.mobile.weather

import tv.own.owntv.mobile.brand.MobileBrand
import kotlin.math.roundToInt

/** The unit a temperature is shown in. */
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

/**
 * The one place a weather temperature is turned into the number on screen.
 *
 * Core's weather comes from Open-Meteo in Celsius, always — `WeatherInfo.temperatureC` — so this is
 * the only conversion there is: the value is converted once, here, and never re-converted by a caller.
 */
object WeatherTemperature {

    /** °F = °C × 9/5 + 32. */
    fun celsiusToFahrenheit(celsius: Float): Float = celsius * 9f / 5f + 32f

    /** Whole degrees in [unit], from the source's Celsius — rounded, not truncated. */
    fun display(celsius: Float, unit: TemperatureUnit): Int = when (unit) {
        TemperatureUnit.CELSIUS -> celsius.roundToInt()
        TemperatureUnit.FAHRENHEIT -> celsiusToFahrenheit(celsius).roundToInt()
    }

    /** Whether the Celsius/Fahrenheit setting means anything here — only when no brand fixes the unit. */
    fun userChoosesUnit(brand: MobileBrand?): Boolean = brand == null

    /**
     * The unit to show. A reseller brand fixes it — every branded app shows Fahrenheit, whatever the
     * shared setting holds — and only an unbranded build ([brand] null) follows the user's
     * Celsius/Fahrenheit setting, as stock OwnTV does.
     */
    fun unitFor(brand: MobileBrand?, userPrefersFahrenheit: Boolean): TemperatureUnit =
        brand?.temperatureUnit
            ?: if (userPrefersFahrenheit) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS
}
