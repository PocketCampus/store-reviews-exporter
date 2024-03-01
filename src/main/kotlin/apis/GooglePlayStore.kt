package apis

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import utils.emptyAsNull
import utils.getLogger
import utils.headTail
import java.io.StringReader

/**
 * A Kotlin representation of the Google Play Store reviews API
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
interface GooglePlayStore {
    /**
     * A coroutine-friendly Play Store API client implementation using Ktor client
     */
    class Client(val credentials: GoogleCredentials) {
        private val client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                })
            }
            install(Logging) {
                logger = Logger.SIMPLE
                level = LogLevel.BODY
            }
        }

        val reviews = Reviews(client, credentials)

        /**
         * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews
         */
        class Reviews(private val client: HttpClient, val credentials: GoogleCredentials) {
            /**
             * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews/list
             * ⚠️ only retrieves the reviews published in the last 7 days! see https://developers.google.com/android-publisher/reply-to-reviews
             */
            suspend fun list(
                packageName: String,
                paginationToken: String? = null,
            ): ReviewsListResponse {
                credentials.refreshIfExpired()
                return client.get("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$packageName/reviews") {
                    headers {
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                        append(HttpHeaders.Authorization, "Bearer ${credentials.accessToken.tokenValue}")
                    }
                    paginationToken?.let { parameter("token", it) }
                }.body<ReviewsListResponse>()
            }
        }
    }

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews/list
     */
    @Serializable
    data class ReviewsListResponse(
        val reviews: List<Review>? = null,
        val tokenPagination: TokenPagination? = null,
        val pageInfo: PageInfo? = null,
    )

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews#Review
     */
    @Serializable
    data class Review(val reviewId: String, val authorName: String, val comments: List<Comment>)

    /**
     * A discriminated union type between UserComment and DeveloperComment
     * @param userComment is a discriminating field in the JSON representation of Comment
     * @param developerComment is a discriminating field in the JSON representation of Comment
     * Only either of the fields may be set at the same time
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews#comment
     */
    @Serializable
    data class Comment(val userComment: UserComment? = null, val developerComment: DeveloperComment? = null)

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews#usercomment
     */
    @Serializable
    data class UserComment(
        val text: String,
        val lastModified: Timestamp,
        val starRating: Int,
        val reviewerLanguage: String,
        val device: String,
        val androidOsVersion: Int,
        val appVersionCode: Int,
        val appVersionName: String,
        val thumbsUpCount: Int,
        val thumbsDownCount: Int,
        val deviceMetadata: DeviceMetadata,
        val originalText: String,
    )

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews#timestamp
     */
    @Serializable
    data class Timestamp(val seconds: String, val nanos: Int) : Comparable<Timestamp> {
        override fun compareTo(other: Timestamp): Int {
            val compareSeconds = seconds.compareTo(other.seconds)
            if (compareSeconds != 0) {
                return compareSeconds
            }
            return nanos.compareTo(other.nanos)
        }
    }

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews#devicemetadata
     */
    @Serializable
    data class DeviceMetadata(
        val productName: String,
        val manufacturer: String,
        val deviceClass: String,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val nativePlatform: String,
        val screenDensityDpi: Int,
        val glEsVersion: Int,
        val cpuModel: String,
        val cpuMake: String,
        val ramMb: Int,
    )

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/reviews#developercomment
     */
    @Serializable
    data class DeveloperComment(val text: String, val lastModified: Timestamp)

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/TokenPagination
     */
    @Serializable
    data class TokenPagination(val nextPageToken: String, val previousPageToken: String)

    /**
     * https://developers.google.com/android-publisher/api-ref/rest/v3/PageInfo
     */
    @Serializable
    data class PageInfo(val totalResults: Int, val resultPerPage: Int, val startIndex: Int)
}

/**
 * Convenience extension function to retrieve all pages of reviews at once
 * ⚠️ only retrieves the reviews published in the last 7 days! see https://developers.google.com/android-publisher/reply-to-reviews
 */
suspend fun GooglePlayStore.Client.Reviews.listAll(
    packageName: String,
    paginationToken: String? = null,
): List<GooglePlayStore.Review> {
    val current = list(packageName, paginationToken)
    return if (current.reviews == null) {
        listOf()
    } else {
        if (current.tokenPagination == null) current.reviews
        else current.reviews + listAll(packageName, current.tokenPagination.nextPageToken)
    }
}

/**
 * Represents a row in a reviews report from CSVs stored in Google Cloud Storage
 */
data class GoogleCloudStorageReportReview(
    val packageName: String?,
    val appVersionCode: String?,
    val appVersionName: String?,
    val reviewerLanguage: String?,
    val device: String?,
    val reviewSubmitDateAndTime: String?,
    val reviewSubmitMillisSinceEpoch: String?,
    val reviewLastUpdateDateAndTime: String?,
    val reviewLastUpdateMillisSinceEpoch: String?,
    val starRating: String?,
    val reviewTitle: String?,
    val reviewText: String?,
    val developerReplyDateAndTime: String?,
    val developerReplyMillisSinceEpoch: String?,
    val developerReplyText: String?,
    val reviewLink: String?,
)

/**
 * Retrieves all archived reviews from Google Cloud Storage
 */
fun GooglePlayStore.Client.Reviews.downloadFromReports(
    gsReportsBucketUri: String,
    packageName: String,
): List<GoogleCloudStorageReportReview> {
    val logger = getLogger()

    val storage = StorageOptions.newBuilder().setCredentials(credentials).build().service
    val (bucketName, reviewsObject) = gsReportsBucketUri.substringAfter("gs://").split("/")
    val bucket = storage.list(bucketName, Storage.BlobListOption.prefix(reviewsObject)).values.toList()
    // filter name, otherwise we get all the reports of the account, including other apps
    val reports = bucket.filter { it.name.contains("reviews_${packageName}_") }
    logger.info { "Found ${reports.size} reports: ${reports.joinToString("\n\t- ") { it.name }}" }
    return reports.flatMap { blob ->
        val text = blob.getContent().toString(Charsets.UTF_16)

        val csvFormat = CSVFormat.DEFAULT.builder().setSkipHeaderRecord(false).build()
        val lines = csvFormat.parse(StringReader(text)).toList()
        val (headers, rows) = lines.headTail()

        if (headers == null) {
            throw Error("Headers missing in reviews report CSV ${blob.name}")
        }

        val indices = headers.withIndex().associate { (index, header) -> header to index }

        rows.map { row ->
            fun valueOf(header: String) = indices[header]?.let { row[it] }.emptyAsNull()
            GoogleCloudStorageReportReview(
                packageName = valueOf("Package Name"),
                appVersionCode = valueOf("App Version Code"),
                appVersionName = valueOf("App Version Name"),
                reviewerLanguage = valueOf("Reviewer Language"),
                device = valueOf("Device"),
                reviewSubmitDateAndTime = valueOf("Review Submit Date and Time"),
                reviewSubmitMillisSinceEpoch = valueOf("Review Submit Millis Since Epoch"),
                reviewLastUpdateDateAndTime = valueOf("Review Last Update Date and Time"),
                reviewLastUpdateMillisSinceEpoch = valueOf("Review Last Update Millis Since Epoch"),
                starRating = valueOf("Star Rating"),
                reviewTitle = valueOf("Review Title"),
                reviewText = valueOf("Review Text"),
                developerReplyDateAndTime = valueOf("Developer Reply Date and Time"),
                developerReplyMillisSinceEpoch = valueOf("Developer Reply Millis Since Epoch"),
                developerReplyText = valueOf("Developer Reply Text"),
                reviewLink = valueOf("Review Link"),
            )
        }
    }
}

