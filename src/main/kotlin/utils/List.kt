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
 * Extension function to split a list with its head and tail parts
 */
fun <T> List<T>.headTail() = Pair(this.firstOrNull(), this.drop(1))

/**
 * Extension function to get the 6th element of a list (allows destructuring syntax)
 */
operator fun <T> List<T>.component6() = this[5]

/**
 * Function to zip many lists together
 */
fun <T> zip(vararg lists: List<T>): List<List<T>> {
    val iterators = lists.map { it.iterator() }
    val results = mutableListOf<List<T>>()
    while (iterators.all { it.hasNext() }) {
        results.add(iterators.map { it.next() })
    }
    return results
}