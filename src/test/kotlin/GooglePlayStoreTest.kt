import apis.GooglePlayStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
class GooglePlayStoreTest {
    @Test
    fun deserializeValidReviewsListResponse() {
        // values for illustrative purposes only
        val json = """
            {
              "reviews": [
                {
                  "reviewId": "1",
                  "authorName": "John Smith",
                  "comments": [
                    {
                      "userComment": {
                        "text": "Hello world",
                        "lastModified": {
                          "seconds": "1701963366",
                          "nanos": 0
                        },
                        "starRating": 2,
                        "reviewerLanguage": "fr",
                        "device": "Samsung Galaxy S10",
                        "androidOsVersion": 12,
                        "appVersionCode": 5,
                        "appVersionName": "pc5",
                        "thumbsUpCount": 10,
                        "thumbsDownCount": 3,
                        "deviceMetadata": {
                          "productName": "Galaxy S10",
                          "manufacturer": "Samsung",
                          "deviceClass": "Phone",
                          "screenWidthPx": 1080,
                          "screenHeightPx": 1920,
                          "nativePlatform": "arm",
                          "screenDensityDpi": 1200,
                          "glEsVersion": 3,
                          "cpuModel": "Cortex ARMv7",
                          "cpuMake": "Cortex",
                          "ramMb": 4096
                        },
                        "originalText": ""
                      }
                    },
                    {
                      "developerComment": {
                        "text": "Thank you",
                        "lastModified": {
                          "seconds": "1701963366",
                          "nanos": 0
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val parsed = Json.decodeFromString<GooglePlayStore.ReviewsListResponse>(json)
        val expected = GooglePlayStore.ReviewsListResponse(
            listOf(
                GooglePlayStore.Review(
                    "1", "John Smith", listOf(
                        GooglePlayStore.Comment(
                            userComment = GooglePlayStore.UserComment(
                                "Hello world",
                                GooglePlayStore.Timestamp("1701963366", 0),
                                2,
                                "fr",
                                "Samsung Galaxy S10",
                                12,
                                5,
                                "pc5",
                                10,
                                3,
                                GooglePlayStore.DeviceMetadata(
                                    "Galaxy S10",
                                    "Samsung",
                                    "Phone",
                                    1080,
                                    1920,
                                    "arm",
                                    1200,
                                    3,
                                    "Cortex ARMv7",
                                    "Cortex",
                                    4096
                                ),
                                ""
                            )
                        ), GooglePlayStore.Comment(
                            developerComment = GooglePlayStore.DeveloperComment(
                                "Thank you", GooglePlayStore.Timestamp("1701963366", 0)
                            )
                        )
                    )
                )
            )
        )
        assertEquals(expected, parsed)
    }

    /**
     * Based on a real response from Google Play Store when the reviews list is empty
     */
    @Test
    fun deserializeEmptyReviewsListResponse() {
        val json = "{}"
        val parsed = Json.decodeFromString<GooglePlayStore.ReviewsListResponse>(json)
        val expected = GooglePlayStore.ReviewsListResponse()
        assertEquals(expected, parsed)
    }
}