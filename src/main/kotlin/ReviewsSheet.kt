import apis.AppleAppStore
import apis.GoogleCloudStorageReportReview
import apis.GooglePlayStore
import apis.GoogleSheets
import apis.GoogleSheets.Spreadsheets.Values.ValueRange
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import utils.getLogger
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * String representation of stores in the sheet
 */
enum class Stores {
  Apple,
  Google,
}

/**
 * Represents a review row in the sheet as unordered data class
 */
data class Review(
  val customer: String? = null,
  val store: String? = null,
  val appId: String? = null,
  val reviewId: String? = null,
  val date: String? = null,
  val title: String? = null,
  val body: String? = null,
  val rating: String? = null,
  val author: String? = null,
  val territory: String? = null,
  val device: String? = null,
  val thumbsUpCount: String? = null,
  val thumbsDownCount: String? = null,
  val androidOsVersion: String? = null,
  val appVersionCode: String? = null,
  val appVersionName: String? = null,
  val deviceProductName: String? = null,
  val deviceManufacturer: String? = null,
  val deviceClass: String? = null,
  val screenWidthPx: String? = null,
  val screenHeightPx: String? = null,
  val nativePlatform: String? = null,
  val screenDensityDpi: String? = null,
  val glEsVersion: String? = null,
  val cpuModel: String? = null,
  val cpuMake: String? = null,
  val ramMb: String? = null,
  val replyText: String? = null,
  val replyDate: String? = null,
  val misc: String? = null,
  val reviewLink: String? = null,
) {
  companion object {
    /**
     * Factory of review row from an Apple CustomerReview
     */
    fun of(customer: Customer, resourceId: String, appleReview: AppleAppStore.CustomerReview): Review = Review(
      customer = customer.name,
      store = Stores.Apple.toString(),
      appId = resourceId,
      reviewId = appleReview.id,
      date = appleReview.attributes.createdDate.toString(),
      title = appleReview.attributes.title,
      body = appleReview.attributes.body,
      rating = appleReview.attributes.rating.toString(),
      author = appleReview.attributes.reviewerNickname,
      territory = appleReview.attributes.territory.toString(),
    )

    /**
     * Factory of review row from a Google Play Store review
     */
    fun of(customer: Customer, packageName: String, googleReview: GooglePlayStore.Review): Review {
      val userComments = googleReview.comments.mapNotNull { it.userComment }
      val developerComments = googleReview.comments.mapNotNull { it.developerComment }

      val review = userComments.minByOrNull { it.lastModified }
      val reply = developerComments.minByOrNull { it.lastModified }

      return Review(
        customer = customer.name,
        store = Stores.Google.toString(),
        appId = packageName,
        reviewId = googleReview.reviewId,
        date = review?.lastModified.toString(),
        body = review?.text?.trim(),
        rating = review?.starRating.toString(),
        author = googleReview.authorName,
        territory = review?.reviewerLanguage.toString(),
        device = review?.device.toString(),
        thumbsUpCount = review?.thumbsUpCount.toString(),
        thumbsDownCount = review?.thumbsDownCount.toString(),
        androidOsVersion = review?.androidOsVersion.toString(),
        appVersionCode = review?.appVersionCode.toString(),
        appVersionName = review?.appVersionName.toString(),
        deviceProductName = review?.deviceMetadata?.productName.toString(),
        deviceManufacturer = review?.deviceMetadata?.manufacturer.toString(),
        deviceClass = review?.deviceMetadata?.deviceClass.toString(),
        screenWidthPx = review?.deviceMetadata?.screenWidthPx.toString(),
        screenHeightPx = review?.deviceMetadata?.screenHeightPx.toString(),
        nativePlatform = review?.deviceMetadata?.nativePlatform.toString(),
        screenDensityDpi = review?.deviceMetadata?.screenDensityDpi.toString(),
        glEsVersion = review?.deviceMetadata?.glEsVersion.toString(),
        cpuModel = review?.deviceMetadata?.cpuModel.toString(),
        cpuMake = review?.deviceMetadata?.cpuMake.toString(),
        ramMb = review?.deviceMetadata?.ramMb.toString(),
        replyDate = reply?.lastModified.toString(),
        replyText = reply?.text.toString(),
        misc = Json.encodeToString(googleReview.comments),
      )
    }

    /**
     * Factory of review row from a Google Cloud Storage Review Report review
     */
    fun of(customer: Customer, googleReportReview: GoogleCloudStorageReportReview): Review =
      Review(customer = customer.name,
        store = Stores.Google.toString(),
        appId = googleReportReview.packageName,
        date = googleReportReview.reviewSubmitDateAndTime,
        body = googleReportReview.reviewText,
        appVersionCode = googleReportReview.appVersionCode,
        appVersionName = googleReportReview.appVersionName,
        title = googleReportReview.reviewTitle,
        territory = googleReportReview.reviewerLanguage,
        device = googleReportReview.device,
        rating = googleReportReview.starRating,
        replyDate = googleReportReview.developerReplyDateAndTime,
        replyText = googleReportReview.developerReplyText,
        reviewLink = googleReportReview.reviewLink,
        reviewId = googleReportReview.reviewLink?.let { Url(it).parameters["reviewId"] })

    /**
     * List of the property names of the Review class as Strings
     */
    val properties = Review::class.memberProperties.map { it.name }.toSet()

    /**
     * Helper for reflection-based instantiation
     */
    private val constructor =
      Review::class.primaryConstructor ?: throw Error("Reflection error: constructor not found for class Review")

    /**
     * Factory of review row from a single row in the Google Sheet
     * @param row a map of sheet headers to cell values (note: expects the serialized null markers to be already converted into actual nulls)
     */
    fun of(row: Map<String, String?>): Review {
      val args = row.mapNotNull { (key, value) ->
        // filter out headers that are not properties
        val param = constructor.parameters.find { it.name == key }
        param?.let { it to value }
      }.toMap()
      return constructor.callBy(args)
    }
  }

  /**
   * Key-value access to properties, like a map
   * @return the String value if the property exists, null otherwise
   */
  fun get(property: String): String? {
    return Review::class.memberProperties.find { it.name == property }?.getter?.call(this)?.toString()
  }
}

/**
 * Logic and models that handle reviews in the Google Sheet
 *
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */
class ReviewsSheet(val spreadsheet: GoogleSheets.Spreadsheets, val sheetName: String) {
  private val logger = getLogger()

  companion object {
    /*
     * Represents null values as string in a cell
     */
    const val NULL_MARKER = "<null>"
  }

  /**
   * Appends the specified rows into the sheet
   */
  suspend fun write(reviews: List<Review>) {
    // retrieve headers at write time
    val (sheetHeaders, _) = getContent()
    val localHeaders = Review.properties

    // all the review properties should be contained in the sheet headers
    if (!sheetHeaders.containsAll(localHeaders)) {
      logger.error(
        "The headers of the reviews sheet does not contain all the available properties of the review map!",
        "This implies that some data will not be written to the sheet and will have to be manually recovered later.",
        "\t Sheet headers: $sheetHeaders",
        "\t Row map property names: $localHeaders",
      )
    }

    // write back values in order of existing headers
    spreadsheet.values.append(
      ValueRange(sheetName, reviews.map { review ->
        sheetHeaders.map { header ->
          review.get(header) ?: NULL_MARKER
        }
      }),
      // IMPORTANT! write value input as RAW otherwise Google Sheets performs some conversion shenanigans (e.g 2.0 -> 2)
      valueInputOption = GoogleSheets.ValueInputOption.RAW
    )
  }

  /**
   * Gets all reviews stored in the sheet
   */
  suspend fun getContent(): Pair<List<String>, List<Review>> {
    val sheet = spreadsheet.values.get(sheetName).values
    if (sheet.isNullOrEmpty()) return Pair(listOf(), listOf())
    val headers = sheet.first()
    val rows = sheet.drop(1)
    return Pair(headers, rows.map { row ->
      // replace null markers by effective null
      val map = headers.zip(row).associate { (header, value) -> header to if (value == NULL_MARKER) null else value }
      Review.of(map)
    })
  }
}
