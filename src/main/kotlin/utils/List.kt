package utils

/**
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */

/**
 * Extension function to pad an array to a given size
 */
fun <T> List<T>.padEnd(size: Int, padValue: T): List<T> = if (this.size >= size) this else {
    val mutable = this.toMutableList()
    mutable.addAll(List(size - this.size) { padValue })
    mutable.toList()
}

/**
 * Extension function to extract the distinct sublist difference from a set of objects
 * @param set the set of objects to exclude from the returned sublist given the identity mapper
 * @param identityMapper a function that identifies the list elements by matching each of them to the type of the set
 */
fun <T, S> List<T>.distinctFrom(set: Set<S>?, identityMapper: (T) -> S) = set?.let {
    this.filter { !set.contains(identityMapper(it)) }
} ?: this

/**
 * Extension function to split a list with its head and tail parts
 */
fun <T> List<T>.headTail() = Pair(this.firstOrNull(), this.drop(1))