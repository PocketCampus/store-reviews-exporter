import Args.Companion.toFlag
import ReviewsSheet.Companion.rowOf
import apis.*
import com.slack.api.Slack
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import utils.*
import kotlin.math.roundToInt
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties

/**
 * Extension function to get the review with the latest date from a list
 */
fun List<Review>?.getLatestDate(): Instant? = this?.mapNotNull {
    val date = it[ReviewsSheet.Headers.Date]
    if (date != null) Instant.parse(date) else null
}?.max()

/**
 * Extension function to convert a list of reviews into a non-null set of review IDs
 */
fun List<Review>?.toIdsSet(): Set<String>? = this?.mapNotNull { it[ReviewsSheet.Headers.ReviewId] }?.toSet()

/**
 * Describes the CLI arguments
 */
data class Args(
    val googleSpreadsheetId: String,
    val googlePrivateKeyPath: String,
    val applePrivateKeyPath: Set<String>,
    val slackWebhook: String,
) {
    companion object {
        /**
         * Reflection-based extension function to stringify an argument name as a flag
         */
        fun <T> KProperty<T>.toFlag(): String = "--${this.name}"

        /**
         * Returns the available flags of the programs, derived by reflection from the properties
         */
        fun getFlags() = Args::class.memberProperties.map { it.toFlag() }
    }
}

fun parseArgs(args: Array<String>): Args {
    val flags = Args.getFlags()
    val helpMessage = "The arguments list should be a list of pairs <--flag> <value>, available flags: $flags, current arguments: ${args.toList()}"

    if (args.size % 2 != 0) {
        throw Error("Wrong number of arguments! $helpMessage")
    }
    val options = args.withIndex().mapNotNull { (index, value) ->
        val isFlag = flags.contains(value)
        if (index % 2 == 0) {
            // every even index (starting at 0) argument should be a flag name
            if (!isFlag) {
                throw Error("Wrong argument flag at index ${index}: ${value}. Should be one of ${flags}. $helpMessage")
            }
            null // return pairs from the values only
        } else {
            // every odd index (starting at 0) should be a value
            if (isFlag) {
                throw Error(
                    "Wrong argument value at index ${index}: ${value}: cannot be a flag name ${flags}. Did you forget to follow an argument flag by its value? $helpMessage"
                )
            }
            args[index - 1] to value // args[index - 1] was checked to exist and be a flag at prev iteration
        }
    }.groupBy({ (key, _) -> key }, { (_, value) -> value }) // group all passed values by flag

    fun <V> Map<String,V>.valueList(prop: KProperty<Any>) = this[prop.toFlag()] ?: throw Error("Argument ${prop.toFlag()} was not specified")

    return Args(
        options.valueList(Args::googleSpreadsheetId).last(),
        options.valueList(Args::googlePrivateKeyPath).last(),
        options.valueList(Args::applePrivateKeyPath).toSet(),
        options.valueList(Args::slackWebhook).last(),
    )
}

suspend fun main(args: Array<String>) {
    val logger = getLogger()

    val (googleSpreadsheetId, googlePrivateKeyPath, applePrivateKeysPaths, slackWebhook) = parseArgs(args)

    val config = Config.load(googleSpreadsheetId, googlePrivateKeyPath, applePrivateKeysPaths, slackWebhook)
    val reviewsSheet = ReviewsSheet(config.spreadsheet, "Reviews")
    val slack = Slack.getInstance()

    try {
        val (sheetHeaders, currentReviews) = reviewsSheet.getContent()
        logger.info { "${currentReviews.size} reviews loaded from reviews sheet" }

        val reviewsByCustomer =
            currentReviews.groupBy { Pair(it[ReviewsSheet.Headers.Customer], it[ReviewsSheet.Headers.Store]) }

        val googlePlayStore = GooglePlayStore.Client(config.googleCredentials)

        val customerToReviews = config.apps.map { (customer, apps) ->
            val (google, apple) = apps
            logger.info { "Processing reviews for customer ${customer.name}" }

            val appleReviews = if (apple == null) listOf() else {
                val existingReviews = reviewsByCustomer[Pair(customer.name, ReviewsSheet.Stores.Apple.name)]
                val latest = existingReviews.getLatestDate()

                logger.info { "Downloading new reviews from Apple Store for customer ${customer.name}" }

                val appleAppStore = AppleAppStore.Client(apple.storeCredentials)

                val fetchedReviews = appleAppStore.getCustomerReviewsWhile(apple.resourceId) {
                    val currentOldest = it.data.minOfOrNull { review -> review.attributes.createdDate }
                    if (currentOldest == null || latest == null) true
                    else currentOldest >= latest
                }
                val newReviews = fetchedReviews.distinctFrom(existingReviews.toIdsSet()) { it.id }
                    .map { rowOf(customer, apple.resourceId, it) }

                logger.info { "Found ${newReviews.size} new reviews from Apple Store for customer ${customer.name}" }

                if (newReviews.isNotEmpty()) {
                    logger.info { "Writing new Apple Store reviews to reviews sheet ${reviewsSheet.spreadsheet.browserUrl}" }
                    reviewsSheet.write(newReviews)
                }

                newReviews
            }

            val (googleReviews, oldGoogleReviews) = if (google == null) Pair(listOf(), listOf()) else {
                val existingReviews = reviewsByCustomer[Pair(customer.name, ReviewsSheet.Stores.Google.name)]

                val oldReviews = if (!existingReviews.isNullOrEmpty()) listOf() else {
                    logger.info { "No Google Play Store reviews found for customer ${customer.name}" }
                    if (google.reviewsReportsBucketUri == null) {
                        logger.info {
                            "No Google Cloud Storage reviews reports bucket URI specified. Previous reviews will not be imported"
                        }
                        listOf()
                    } else {
                        logger.info {
                            "Retrieving older reviews from Google Cloud Storage bucket ${
                                google.reviewsReportsBucketUri
                            }"
                        }

                        val oldReviews = googlePlayStore.reviews.downloadFromReports(
                            google.reviewsReportsBucketUri, google.packageName
                        ).map { rowOf(customer, it) }

                        logger.info {
                            "Found ${oldReviews.size} reviews from Google Cloud Storage bucket of reviews reports"
                        }

                        if (oldReviews.isNotEmpty()) {
                            logger.info {
                                "Writing old Google Play Store reviews to reviews sheet ${
                                    reviewsSheet.spreadsheet.browserUrl
                                }"
                            }
                            reviewsSheet.write(oldReviews)
                        }
                        oldReviews
                    }
                }

                logger.info { "Downloading new reviews from Google Play Store for customer ${customer.name}" }

                val fetchedReviews = googlePlayStore.reviews.listAll(google.packageName)
                val newReviews = fetchedReviews.distinctFrom(existingReviews.toIdsSet()) { it.reviewId }
                    .map { rowOf(customer, google.packageName, it) }

                logger.info { "Found ${newReviews.size} new reviews from Google Play Store for customer ${customer.name}" }

                if (newReviews.isNotEmpty()) {
                    logger.info {
                        "Writing new Google Play Store reviews to reviews sheet ${reviewsSheet.spreadsheet.browserUrl}"
                    }
                    reviewsSheet.write(newReviews)
                }

                Pair(newReviews, oldReviews)
            }

            logger.info(
                "${customer.name}: wrote ${appleReviews.size} new Apple App Store reviews and ${googleReviews.size} new Google Play Store reviews",
                "\t Apple reviews IDs: ${appleReviews.map { it[ReviewsSheet.Headers.ReviewId] }}",
                "\t Google reviews IDs: ${googleReviews.map { it[ReviewsSheet.Headers.ReviewId] }}",
                "\t Retrieved ${oldGoogleReviews.size} reviews from Google Play Store review reports in Cloud Storage"
            )

            customer to mapOf(
                "Apple App Store" to appleReviews,
                "Google Play Store" to googleReviews,
                "Google Play Store Review Reports" to oldGoogleReviews
            )
        }.toMap()

        val writtenReviews = customerToReviews.filterValues {
            it.values.any { reviews -> reviews.isNotEmpty() }
        }

        val counts = writtenReviews.mapValues {
            it.value.mapValues { entry -> entry.value.size }
        }

        val totalCount = counts.values.sumOf { it.values.sum() }

        if (totalCount > 0) {
            val averages = writtenReviews.mapValues {
                it.value.mapValues { entry ->
                    entry.value.mapNotNull {
                        tryOrNull {
                            Integer.parseInt(
                                it[ReviewsSheet.Headers.Rating]
                            )
                        }
                    }.average().nanAsNull()
                }
            }

            val customerAverage = averages.mapValues {
                it.value.values.filterNotNull().average().nanAsNull()
            }
            val totalAverage = customerAverage.values.filterNotNull().average().nanAsNull()

            logger.info { "Sending message with imported reviews to Slack" }

            val response = slack.send(config.slackReviewsWebhook) {
                fun Int?.toRatingStars(): String = if (this == null) "" else "★".repeat(this) + "☆".repeat(5 - this)

                fun Double?.toRatingStars(): String =
                    this?.let { if (it.isNaN()) null else it.roundToInt() }.toRatingStars()

                fun String?.toRatingStars(): String = tryOrNull { this?.toDouble() }.toRatingStars()

                blocks {
                    section {
                        markdownText(buildString {
                            append("*Beep boop 🤖 I have found new reviews* (last run on ${
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
                                "\n⭐ Processed *$totalCount reviews* to the <${
                                    reviewsSheet.spreadsheet.browserUrl
                                }|PocketCampus App Reviews sheet>"
                            )
                            append(
                                "\nAverage rating over all reviews: ${totalAverage.toRatingStars()} (${
                                    String.format(
                                        "%.2f", totalAverage
                                    )
                                })\n"
                            )
                        })
                    }
                    divider()
                    writtenReviews.forEach { app ->
                        val (customer, platforms) = app
                        section {
                            markdownText(buildString {
                                append("📱 *${customer.name}*")
                                append(
                                    "\nAverage rating of imported reviews for this customer: ${customerAverage[customer].toRatingStars()} (${
                                        String.format(
                                            "%.2f", totalAverage
                                        )
                                    })"
                                )
                            })
                        }
                        platforms.entries.filter { it.value.isNotEmpty() }.forEach { entry ->
                            val (platform, reviews) = entry
                            val sortedReviews = reviews.sortedByDescending { it[ReviewsSheet.Headers.Date] }
                            val dateRange = sortedReviews.mapNotNull {
                                it[ReviewsSheet.Headers.Date]?.let { date ->
                                    Instant.parse(date).toLocalDateTime(TimeZone.UTC).date
                                }
                            }.let { it.min()..it.max() }
                            val lastReviews = sortedReviews.take(3)
                            val average = averages[customer]?.get(platform)

                            section {
                                markdownText(buildString {
                                    append("🛍️ Found *${reviews.size}* reviews on *$platform*")
                                    append("\nPeriod: ${dateRange.start} to ${dateRange.endInclusive}")
                                    append(
                                        "\nAverage rating of imported reviews for this platform: ${
                                            average.toRatingStars()
                                        } (${
                                            String.format(
                                                "%.2f", totalAverage
                                            )
                                        })"
                                    )
                                    append("\nShowing ${lastReviews.size} latest reviews:")
                                })
                            }
                            lastReviews.forEach {
                                divider()
                                section {
                                    markdownText(buildString {
                                        append("*Date:* ${it[ReviewsSheet.Headers.Date]}")
                                        append("\n*Rating:* ${it[ReviewsSheet.Headers.Rating].toRatingStars()}")
                                        append("\n*Author:* ${it[ReviewsSheet.Headers.Author]}")
                                        append("\n*Review ID:* ${it[ReviewsSheet.Headers.ReviewId]}")
                                        append("\n*Title:* ${it[ReviewsSheet.Headers.Title]}")
                                        append("\n> ${it[ReviewsSheet.Headers.Body]?.replace("\n", "\n> ")}")
                                    })
                                }
                            }
                        }
                        divider()
                    }
                }
            }

            logger.info { response.toString() }
            if (response == null || response.code >= 400) {
                throw Error("Failed to send Slack message: " + response?.body)
            }
        }
    } catch (error: Throwable) {
        slack.send(config.slackReviewsWebhook, "🤖⚠️ The app reviews tool encountered an error:\n${error.message}")
        throw error
    }
}
