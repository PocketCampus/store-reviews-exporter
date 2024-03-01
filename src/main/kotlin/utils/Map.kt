package utils

/**
 * Utility extension function that attempts to get from a map, or throws instead
 * Also useful when nullable values are undesirable and should throw (the resulting type is non-nullable)
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
fun <K, V> Map<K, V>.getOrThrow(key: K) = this.getOrElse(key) { throw Error(key.toString() + " key is missing") }