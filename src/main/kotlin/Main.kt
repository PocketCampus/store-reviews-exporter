import ReviewsSheet.Companion.rowOf
import apis.*
import com.slack.api.Slack
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import utils.distinctFrom
import utils.getLogger
import utils.of

fun main(args: Array<String>) {
  runBlocking {
    val slack = Slack.getInstance()
    val (googleSpreadsheetId, googlePrivateKeyPath, applePrivateKeysPaths, slackWebhook) = Args.parse(args)

    try {
      val logger = getLogger()

      val config = Config.load(googleSpreadsheetId, googlePrivateKeyPath, applePrivateKeysPaths)
      val reviewsSheet = ReviewsSheet(config.spreadsheet, "Reviews")
      val (_, currentReviews) = reviewsSheet.getContent()
      logger.info { "${currentReviews.size} reviews loaded from reviews sheet" }

      val reviewsByCustomer =
        currentReviews.groupBy { Pair(it[ReviewsSheet.Headers.Customer], it[ReviewsSheet.Headers.Store]) }

      val googlePlayStore = GooglePlayStore.Client(config.googleCredentials)

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
       * Helper to group the logic to fetch Apple reviews
       * @return a list of reviews that were not previously imported into the sheet
       */
      suspend fun getAppleReviews(customer: Customer, app: AppleApp): List<Review> {
        val existingReviews = reviewsByCustomer[Pair(customer.name, ReviewsSheet.Stores.Apple.name)]
        val latest = existingReviews.getLatestDate()

        logger.info { "Downloading new reviews from Apple Store for customer ${customer.name}" }

        val appleAppStore = AppleAppStore.Client(app.storeCredentials)

        val fetchedReviews = appleAppStore.getCustomerReviewsWhile(app.resourceId) {
          val currentOldest = it.data.minOfOrNull { review -> review.attributes.createdDate }
          if (currentOldest == null || latest == null) true
          else currentOldest >= latest
        }
        val newReviews =
          fetchedReviews.distinctFrom(existingReviews.toIdsSet()) { it.id }.map { rowOf(customer, app.resourceId, it) }

        logger.info { "Found ${newReviews.size} new reviews from Apple Store for customer ${customer.name}" }

        if (newReviews.isNotEmpty()) {
          logger.info { "Writing new Apple Store reviews to reviews sheet ${reviewsSheet.spreadsheet.browserUrl}" }
          reviewsSheet.write(newReviews)
        }

        return newReviews
      }

      /**
       * Helper to group the logic to fetch Google reviews that are already archived in a Google Cloud Storage bucket
       * @return a list of reviews that were not previously imported into the sheet
       */
      suspend fun getArchivedGoogleReviews(
        customer: Customer,
        app: AndroidApp,
      ): List<Review> {
        if (app.reviewsReportsBucketUri == null) {
          throw Error(
            "No Google Cloud Storage reviews reports bucket URI specified. Previous reviews will not be imported"
          )
        }
        val existingReviews = reviewsByCustomer[Pair(customer.name, ReviewsSheet.Stores.Google.name)]

        logger.info {
          "Retrieving older reviews from Google Cloud Storage bucket ${
            app.reviewsReportsBucketUri
          }"
        }

        val archivedReviews = googlePlayStore.reviews.downloadFromReports(
          app.reviewsReportsBucketUri, app.packageName
        ).map { rowOf(customer, it) }

        logger.info {
          "Found ${archivedReviews.size} reviews from Google Cloud Storage bucket of reviews reports"
        }

        val unprocessedReviews =
          archivedReviews.distinctFrom(existingReviews.toIdsSet()) { it[ReviewsSheet.Headers.ReviewId] }

        logger.info {
          "Of which ${unprocessedReviews.size} reviews are not imported from Google Cloud Storage bucket of reviews reports"
        }

        if (unprocessedReviews.isNotEmpty()) {
          logger.info {
            "Writing missing archived Google Play Store reviews to reviews sheet ${
              reviewsSheet.spreadsheet.browserUrl
            }"
          }
          reviewsSheet.write(unprocessedReviews)
        }

        return unprocessedReviews
      }

      /**
       * Helper to group the logic to fetch the list of recent Google reviews
       * @see GooglePlayStore.Client.Reviews.listAll
       * @return a list of reviews that were not previously imported into the sheet
       */
      suspend fun getRecentGoogleReviews(customer: Customer, app: AndroidApp): List<Review> {
        val existingReviews = reviewsByCustomer[Pair(customer.name, ReviewsSheet.Stores.Google.name)]

        logger.info { "Downloading new reviews from Google Play Store for customer ${customer.name}" }

        val fetchedReviews = googlePlayStore.reviews.listAll(app.packageName)
        val newReviews = fetchedReviews.distinctFrom(existingReviews.toIdsSet()) { it.reviewId }
          .map { rowOf(customer, app.packageName, it) }

        logger.info { "Found ${newReviews.size} new reviews from Google Play Store for customer ${customer.name}" }

        if (newReviews.isNotEmpty()) {
          logger.info {
            "Writing new Google Play Store reviews to reviews sheet ${reviewsSheet.spreadsheet.browserUrl}"
          }
          reviewsSheet.write(newReviews)
        }

        return newReviews
      }

      // launch all requests in parallel
      val deferredFetches = config.apps.mapValues { (customer, apps) ->
        val (googleConfig, appleConfig) = apps
        logger.info { "Processing reviews for customer ${customer.name}" }

        val appleReviews = async {
          Result.of {
            val apple = appleConfig.getOrThrow()
            getAppleReviews(customer, apple)
          }
        }
        val googleReviews = async {
          Result.of {
            val google = googleConfig.getOrThrow()
            getRecentGoogleReviews(customer, google)
          }
        }
        val archivedGoogleReviews = async {
          Result.of {
            val google = googleConfig.getOrThrow()
            getArchivedGoogleReviews(customer, google)
          }
        }

        mapOf(
          "🍏 Apple App Store" to appleReviews,
          "🤖 Google Play Store (Recent)" to googleReviews,
          "🤖📁 Google Play Store (Storage)" to archivedGoogleReviews,
        )
      }

      // await all results before reporting
      val responses =
        deferredFetches.mapValues { (_, stores) -> stores.mapValues { (_, deferred) -> deferred.await() } }

      val results = responses.values.flatMap { it.values.map { result -> result } }
      val newReviews = results.flatMap { result ->
        result.fold(onFailure = { listOf() }, onSuccess = { reviews -> reviews })
      }

      // log all failures for convenience
      val failures = results.mapNotNull { it.fold(onSuccess = { null }, onFailure = { error -> error } ) }
      failures.forEach {
        logger.error(it.toString())
      }

      if (newReviews.isNotEmpty() || failures.isNotEmpty()) {
        val stats = if (newReviews.isNotEmpty()) computeStats(newReviews) else null
        slack.sendOrThrow(slackWebhook) {
          blocks {
            reportSummary(stats, reviewsSheet.spreadsheet.browserUrl, this)
            responses.forEach { (customer, stores) ->
              reportCustomer(customer, stores, this)
            }
          }
        }
      }

    } catch (error: Throwable) {
      // last chance for error reporting
      slack.sendOrLog(slackWebhook) { "🤖⚠️ The app reviews tool encountered an error:\n${error.message}" }
      throw error
    }
  }
}

