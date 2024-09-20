package utils

import java.util.*

/**
 * Extension function to treat an empty String as null
 */
fun String?.emptyAsNull(): String? = if (this.isNullOrEmpty()) null else this

/**
 * Extension function to check if all strings in an Array are null or empty
 */
fun List<String?>.anyNullOrEmpty(): Boolean = this.any { it.emptyAsNull() == null }

/**
 * Extension function to capitalize a string
 */
fun String.capitalized(): String {
    return this.lowercase().replaceFirstChar {
        if (it.isLowerCase())
            it.titlecase(Locale.getDefault())
        else it.toString()
    }
}