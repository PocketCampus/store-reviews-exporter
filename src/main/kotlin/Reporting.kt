import apis.send
import com.slack.api.Slack
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import com.slack.api.webhook.Payload.PayloadBuilder
import com.slack.api.webhook.WebhookResponse
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import utils.capitalized
import utils.tryOrNull
import kotlin.math.roundToInt

/**
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */

/**
 * Helper extension function to send a Slack message or throws if the webhook results in an HTTP error response
 */
fun Slack.sendOrThrow(url: String, configurePayload: PayloadBuilder.() -> Unit): WebhookResponse {
  val response = this.send(url, configurePayload)
  if (response == null || response.code >= 400) {
    throw Error("Failed to send Slack message: ${response?.body ?: ""} ($response)")
  }
  return response
}

/**
 * Helper extension function to send a Slack message or give up on error and log the stack trace
 */
fun Slack.sendOrLog(url: String, configurePayload: PayloadBuilder.() -> Unit) = try {
  this.sendOrThrow(url, configurePayload)
} catch (error: Throwable) {
  error.printStackTrace()
}

/**
 * Stats for a set of reviews
 */
data class Stats(
  val count: Int,
  val average: Double,
)

/**
 * Compute stats for a list of reviews
 */
fun computeStats(reviews: List<Review>): Stats {
  val count = reviews.size
  val average = reviews.mapNotNull {
    tryOrNull {
      Integer.parseInt(
        it.rating
      )
    }
  }.average()
  return Stats(count, average)
}

/**
 * Formats an Int into a 5 stars review display
 */
fun Int?.toRatingStars(): String = if (this == null) "" else "★".repeat(this) + "☆".repeat(5 - this)

/**
 * Formats a Double into a 5 stars review display
 */
fun Double?.toRatingStars(): String = this?.let { if (it.isNaN()) null else it.roundToInt() }.toRatingStars()

/**
 * Formats a String double into a 5 stars review display
 */
fun String?.toRatingStars(): String = tryOrNull { this?.toDouble() }.toRatingStars()

/**
 * Formats a Slack message section with the report summary
 */
fun reportSummary(stats: Stats?, reviewsSheetUrl: String, blocksBuilder: LayoutBlockDsl) {
  val message = stats?.let { "I have found new reviews" } ?: "Errors found while fetching reviews"
  blocksBuilder.apply {
    section {
      markdownText(buildString {
        append("*Beep boop 🤖 $message* (last run on ${
          Clock.System.now().toLocalDateTime(TimeZone.UTC).let {
            "${
              it.month.toString().capitalized()
            } ${
              it.dayOfMonth
            }, ${it.year} at ${it.hour}:${it.minute})"
          }
        }")
        append("\n\n")
        append(
          "\n⭐ Processed *${stats?.count ?: "0"} reviews* to the <${
            reviewsSheetUrl
          }|PocketCampus App Reviews sheet>"
        )
        stats?.let {
          append(
            "\nAverage rating over all new reviews: ${stats.average.toRatingStars()} (${
              String.format(
                "%.2f", stats.average
              )
            })\n"
          )
        }
      })
    }
  }
}

/**
 * Formats a Slack message section for a single review
 */
fun reportReview(review: Review, blocksBuilder: LayoutBlockDsl) {
  blocksBuilder.apply {
    section {
      markdownText(buildString {
        append("*Date:* ${review.date}")
        append("\n*Rating:* ${review.rating.toRatingStars()}")
        append("\n*Author:* ${review.author}")
        append("\n*Review ID:* ${review.reviewId}")
        append("\n*Title:* ${review.title}")
        append("\n> ${review.body?.replace("\n", "\n> ")}")
      })
    }
  }
}

/**
 * Formats a Slack message fragment for a set of reviews in a given store for a customer
 */
fun reportStore(store: String, result: Result<List<Review>>, blocksBuilder: LayoutBlockDsl) {
  blocksBuilder.apply {
    result.fold(onSuccess = { reviews ->
      if (reviews.isEmpty()) {
        return
      }
      val reviewsByRecent = reviews.sortedByDescending { it.date }
      val dateRange = reviewsByRecent.mapNotNull {
        it.date?.let { date ->
          Instant.parse(date).toLocalDateTime(TimeZone.UTC).date
        }
      }.let { it.min()..it.max() }
      val lastReviews = reviewsByRecent.filter { !it.body.isNullOrEmpty() }.take(3)
      val stats = computeStats(reviews)

      section {
        markdownText(buildString {
          append("🛍️ Found *${reviews.size}* reviews on *$store*")
          append("\nPeriod: ${dateRange.start} to ${dateRange.endInclusive}")
          append(
            "\nAverage rating of imported reviews for this platform: ${
              stats.average.toRatingStars()
            } (${
              String.format(
                "%.2f", stats.average
              )
            })"
          )
          if (lastReviews.isNotEmpty()) {
            append("\nShowing ${lastReviews.size} latest reviews with comments:")
          }
        })
      }
      lastReviews.forEach {
        divider()
        reportReview(it, blocksBuilder)
      }
    }, onFailure = { error ->
      section {
        markdownText(buildString {
          append("️🛑 Encountered the following error while fetching reviews on $store:")
          append("\n```${error.message}```")
        })
      }
    })
  }
}


/**
 * Formats a Slack message fragment for a customer
 */
fun reportCustomer(customer: Customer, storeReviews: Map<String, Result<List<Review>>>, blocksBuilder: LayoutBlockDsl) {
  blocksBuilder.apply {
    val allReviews =
      storeReviews.values.flatMap { it.fold(onSuccess = { reviews -> reviews }, onFailure = { listOf() }) }
    if (storeReviews.values.any { it.isFailure } || allReviews.isNotEmpty()) {
      divider()
      section {
        markdownText(buildString {
          append("📱 *${customer.name}*")
          if (allReviews.isNotEmpty()) {
            val stats = computeStats(allReviews)
            append(
              "\nAverage rating of imported reviews for this customer: ${stats.average.toRatingStars()} (${
                String.format(
                  "%.2f", stats.average
                )
              })"
            )
          }
        })
      }
      storeReviews.forEach { (store, result) ->
        reportStore(store, result, blocksBuilder)
      }
    }
  }
}