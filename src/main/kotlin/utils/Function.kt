package utils

/**
 * Helper function to retry a block of code until a success condition is accepted (given a max number of retries)
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
suspend fun <T> retry(maxRetries: Int, successFn: (T) -> Boolean, fn: suspend () -> T): T {
    val res = fn()
    return if (successFn(res) || maxRetries == 0) res else retry(maxRetries - 1, successFn, fn)
}

/**
 * Helper function to try a function, else return null if an exception is caught
 */
inline fun <T> tryOrNull(f: () -> T) =
    try {
        f()
    } catch (_: Throwable) {
        null
    }