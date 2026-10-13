package de.radicalfishgames.crosscode

// The frame-rate limit: which rates a screen is offered, and which screen refresh rate suits a limit.
// OFF (0) means no limit: the game draws on every screen refresh.
object FrameRate {

    const val OFF = 0
    val CHOICES = listOf(30, 45, 60, 90, 120)

    // The limits a screen is offered: those it can actually show, given its refresh rates.
    fun choicesFor(refreshRates: List<Int>): List<Int> {
        val top = refreshRates.maxOrNull() ?: 60
        return CHOICES.filter { it <= top + 1 }.ifEmpty { listOf(CHOICES.first()) }
    }

    // The refresh rate to run the screen at for a limit: the lowest one the limit divides evenly
    // (45 gets 90 Hz where there is one; 30 and 60 do not hold a 120 Hz panel at 120), else the
    // highest. With no limit it is the highest. Null when the screen reports no rates.
    fun refreshFor(limit: Int, refreshRates: List<Int>): Int? {
        val rates = refreshRates.filter { it > 0 }.sorted()
        if (rates.isEmpty()) return null
        if (limit == OFF) return rates.last()
        return rates.firstOrNull { it >= limit && it % limit == 0 } ?: rates.last()
    }

    // A stored limit as one this screen offers: OFF stays OFF, anything else snaps to the nearest
    // choice (a tie goes to the lower rate).
    fun normalize(stored: Int, choices: List<Int>): Int =
        if (stored <= 0) OFF else choices.minByOrNull { kotlin.math.abs(it - stored) * 2 + (if (it > stored) 1 else 0) } ?: OFF

    // The value after `limit` in the cycle Off, then each choice, then Off again.
    fun next(limit: Int, choices: List<Int>): Int {
        if (limit == OFF) return choices.first()
        val i = choices.indexOf(limit)
        return if (i < 0 || i == choices.size - 1) OFF else choices[i + 1]
    }

    fun label(limit: Int): String = if (limit == OFF) "Off" else "$limit fps"
}
