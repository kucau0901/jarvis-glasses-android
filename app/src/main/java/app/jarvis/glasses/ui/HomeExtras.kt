package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.Can
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// What an opened tile draws beyond words, as on Jarvis's web app (src/app/ui/HomeChart.ts,
// HomeMore.ts, Home.ts): a line chart, a slider's start, a mode's name, the empty Home's words.
// Plain Kotlin, to be tested.

/** A number as a chart labels it: whole when it is large, to a hundredth when it is small. */
internal fun chartNumber(v: Double): String {
  if (abs(v) >= 100) return v.roundToLong().toString()
  val r = (v * 100).roundToLong() / 100.0
  return if (r == floor(r)) r.toLong().toString() else r.toString()
}

/** Where each value goes in a [w] by [h] box with [pad] around: evenly across, low to high up the box. */
internal fun chartLine(values: List<Double>, w: Float, h: Float, pad: Float): List<Pair<Float, Float>> {
  if (values.isEmpty()) return emptyList()
  val lo = values.min()
  val span = (values.max() - lo).takeIf { it != 0.0 } ?: 1.0
  val steps = maxOf(1, values.size - 1)
  return values.mapIndexed { i, v -> (pad + i * (w - 2 * pad) / steps) to (h - pad - ((v - lo) * (h - 2 * pad) / span)).toFloat() }
}

/** [v] on a slider's grid, which starts at [min] and goes up by [step], no higher than [max]. */
internal fun onStep(v: Double, min: Double, max: Double, step: Double): Double =
    (min + ((v - min) / step).roundToInt() * step).coerceIn(min, sliderTop(min, max, step))

/** A slider's highest stop: [max], or the last step below it when the range isn't whole steps. */
internal fun sliderTop(min: Double, max: Double, step: Double): Double = min + floor((max - min) / step + 1e-9) * step

/** A slider's start: the middle of its range, on a step. */
internal fun sliderStart(min: Double, max: Double, step: Double): Double = onStep((min + max) / 2, min, max, step)

/** The stops between a slider's ends, as Material's Slider counts them. */
internal fun sliderSteps(min: Double, max: Double, step: Double): Int = (((sliderTop(min, max, step) - min) / step).roundToInt() - 1).coerceAtLeast(0)

/**
 * A number to send or show, with as many decimals as its step has and no more, and a point whatever
 * the phone's language: Jarvis reads "23,5" as no number at all.
 */
internal fun stepWords(v: Double, step: Double): String {
  val decimals = java.math.BigDecimal(step.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
  return java.math.BigDecimal(v.toString()).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** An aircond's next set point, [by] from [target] within its range, to a tenth, as the web works it out. */
internal fun nextTemp(target: Double, by: Double, min: Double, max: Double): Double = ((target + by).coerceIn(min, max) * 10).roundToLong() / 10.0

/**
 * A number as typed: "23.5", "23,5" (a decimal comma), "1,000" or "1 000" (thousands). Anything else
 * is no number, rather than a guess that makes an alert at the wrong value.
 */
internal fun parseNumber(typed: String): Double? {
  val t = typed.trim().replace(" ", "")
  return when {
    Regex("""-?\d+(\.\d+)?""").matches(t) -> t.toDouble()
    Regex("""-?\d{1,3}(,\d{3})+(\.\d+)?""").matches(t) -> t.replace(",", "").toDouble()
    Regex("""-?\d+,\d{1,2}""").matches(t) -> t.replace(',', '.').toDouble()
    else -> null
  }
}

/** A mode or fan speed as a button says it: "fan_only" is "Fan only". */
internal fun pretty(word: String): String = word.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** "a", "a and b", "a, b and c". */
internal fun inWords(items: List<String>): String =
    if (items.size <= 1) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()

/** What an empty Home says, with what a panel could show this person. */
internal fun emptyHomeText(can: Can?): String {
  val home = can?.home == true
  val car = can?.car == true
  val example = if (home) "“put the gate and the porch light on a panel”" else if (car) "“put my car on a panel”" else "“make me a to-do panel”"
  val shows = listOfNotNull("things in the house".takeIf { home }, "your car".takeIf { car }, "what you have used", "notes")
  return "Ask Jarvis for a panel and it appears here: $example. A panel can show ${inWords(shows)}."
}
