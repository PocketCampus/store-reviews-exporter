import apis.AppleAppStore
import apis.GoogleCloudStorageReportReview
import apis.GooglePlayStore
import apis.GoogleSheets
import apis.GoogleSheets.Spreadsheets.Values.ValueRange
import io.ktor.http.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import utils.getLogger

typealias Review = Map<ReviewsSheet.Headers, String?>

/**
 * Logic and models that handle reviews in the Google Sheet
 *
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */
class ReviewsSheet(val spreadsheet: GoogleSheets.Spreadsheets, val sheetName: String) {
    private val logger = getLogger()

    /**
     * String representation of stores in the sheet
     */
    enum class Stores {
        Apple,
        Google,
    }

    /**
     * Header keys for review rows (used as sheet headers)
     */
    enum class Headers {
        Customer,
        Store,
        AppId,
        ReviewId,
        Date,
        Title,
        Body,
        Rating,
        Author,
        Territory,
        Device,
        ThumbsUpCount,
        ThumbsDownCount,
        AndroidOsVersion,
        AppVersionCode,
        AppVersionName,
        DeviceProductName,
        DeviceManufacturer,
        DeviceClass,
        ScreenWidthPx,
        ScreenHeightPx,
        NativePlatform,
        ScreenDensityDpi,
        GlEsVersion,
        CpuModel,
        CpuMake,
        RamMb,
        ReplyText,
        ReplyDate,
        Misc,
        ReviewLink,
    }

    companion object {
        /*
         * Represents null values as string in a cell
         */
        private const val NULL_MARKER = "<null>"

        /**
         * Factory of review row from an Apple CustomerReview
         */
        fun rowOf(customer: Customer, resourceId: String, appleReview: AppleAppStore.CustomerReview): Review = mapOf(
            Headers.Customer to customer.name,
            Headers.Store to Stores.Apple.toString(),
            Headers.AppId to resourceId,
            Headers.ReviewId to appleReview.id,
            Headers.Date to appleReview.attributes.createdDate.toString(),
            Headers.Title to appleReview.attributes.title,
            Headers.Body to appleReview.attributes.body,
            Headers.Rating to appleReview.attributes.rating.toString(),
            Headers.Author to appleReview.attributes.reviewerNickname,
            Headers.Territory to appleReview.attributes.territory.toString(),
        )

        /**
         * Factory of review row from a Google Play Store review
         */
        fun rowOf(customer: Customer, packageName: String, googleReview: GooglePlayStore.Review): Review {
            val userComments = googleReview.comments.mapNotNull { it.userComment }
            val developerComments = googleReview.comments.mapNotNull { it.developerComment }

            val review = userComments.minByOrNull { it.lastModified }
            val reply = developerComments.minByOrNull { it.lastModified }

            return mapOf(
                Headers.Customer to customer.name,
                Headers.Store to Stores.Google.toString(),
                Headers.AppId to packageName,
                Headers.ReviewId to googleReview.reviewId,
                Headers.Date to review?.lastModified.toString(),
                Headers.Body to review?.text?.trim(),
                Headers.Rating to review?.starRating.toString(),
                Headers.Author to googleReview.authorName,
                Headers.Territory to review?.reviewerLanguage.toString(),
                Headers.Device to review?.device.toString(),
                Headers.ThumbsUpCount to review?.thumbsUpCount.toString(),
                Headers.ThumbsDownCount to review?.thumbsDownCount.toString(),
                Headers.AndroidOsVersion to review?.androidOsVersion.toString(),
                Headers.AppVersionCode to review?.appVersionCode.toString(),
                Headers.AppVersionName to review?.appVersionName.toString(),
                Headers.DeviceProductName to review?.deviceMetadata?.productName.toString(),
                Headers.DeviceManufacturer to review?.deviceMetadata?.manufacturer.toString(),
                Headers.DeviceClass to review?.deviceMetadata?.deviceClass.toString(),
                Headers.ScreenWidthPx to review?.deviceMetadata?.screenWidthPx.toString(),
                Headers.ScreenHeightPx to review?.deviceMetadata?.screenHeightPx.toString(),
                Headers.NativePlatform to review?.deviceMetadata?.nativePlatform.toString(),
                Headers.ScreenDensityDpi to review?.deviceMetadata?.screenDensityDpi.toString(),
                Headers.GlEsVersion to review?.deviceMetadata?.glEsVersion.toString(),
                Headers.CpuModel to review?.deviceMetadata?.cpuModel.toString(),
                Headers.CpuMake to review?.deviceMetadata?.cpuMake.toString(),
                Headers.RamMb to review?.deviceMetadata?.ramMb.toString(),
                Headers.ReplyDate to reply?.lastModified.toString(),
                Headers.ReplyText to reply?.text.toString(),
                Headers.Misc to Json.encodeToString(googleReview.comments),
            )
        }

        /**
         * Factory of review row from a Google Cloud Storage Review Report review
         */
        fun rowOf(customer: Customer, googleReportReview: GoogleCloudStorageReportReview): Review =
            mapOf(Headers.Customer to customer.name,
                Headers.Store to Stores.Google.toString(),
                Headers.AppId to googleReportReview.packageName,
                Headers.Date to googleReportReview.reviewSubmitDateAndTime,
                Headers.Body to googleReportReview.reviewText,
                Headers.AppVersionCode to googleReportReview.appVersionCode,
                Headers.AppVersionName to googleReportReview.appVersionName,
                Headers.Title to googleReportReview.reviewTitle,
                Headers.Territory to googleReportReview.reviewerLanguage,
                Headers.Device to googleReportReview.device,
                Headers.Rating to googleReportReview.starRating,
                Headers.ReplyDate to googleReportReview.developerReplyDateAndTime,
                Headers.ReplyText to googleReportReview.developerReplyText,
                Headers.ReviewLink to googleReportReview.reviewLink,
                Headers.ReviewId to googleReportReview.reviewLink?.let { Url(it).parameters["reviewId"] })

        /**
         * Factory of review row from a single row in the Google Sheet
         */
        private fun rowOf(values: List<String>, headers: List<String>): Review {
            val sheetRow = headers.zip(values).associate { (header, value) -> header to value }
            return Headers.entries.associateWith {
                val cellValue = sheetRow[it.name]
                if (cellValue == NULL_MARKER) null else cellValue
            }
        }

    }

    /**
     * Appends the specified rows into the sheet
     */
    suspend fun write(rows: List<Review>) {
        // retrieve headers at write time
        val (sheetHeaders, _) = getContent()
        val localHeaders = Headers.entries.map { it.name }

        // all the row properties should be contained in the sheet headers
        if (!sheetHeaders.containsAll(localHeaders)) {
            logger.error(
                "The headers of the reviews sheet does not contain all the available properties of the review map!",
                "This implies that some data will not be written to the sheet and will have to be manually recovered later.",
                "\t Sheet headers: $sheetHeaders",
                "\t Row map property names: $localHeaders",
            )
        }

        // write back values in order of existing headers
        val orderedHeaders = sheetHeaders.mapNotNull { header -> Headers.entries.firstOrNull { it.name == header } }

        spreadsheet.values.append(
            ValueRange(sheetName, rows.map { row ->
                orderedHeaders.map {
                    row[it] ?: NULL_MARKER
                }
            })
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
        return Pair(headers, rows.map { rowOf(it, headers) })
    }
}
