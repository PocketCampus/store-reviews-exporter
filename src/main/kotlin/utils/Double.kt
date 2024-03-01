package utils

/**
 * Extension function that transforms a Double into null if it is NaN, else the double value
 */
fun Double.nanAsNull() = if (this.isNaN()) null else this