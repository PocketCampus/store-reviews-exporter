package utils

/**
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */
suspend fun <T> Result.Companion.of(throwingFunction: suspend () -> T): Result<T> {
  return try {
    success(throwingFunction())
  } catch (e: Throwable) {
    failure(e)
  }
}