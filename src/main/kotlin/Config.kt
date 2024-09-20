import apis.AppleAppStore
import apis.GoogleSheets
import com.google.auth.oauth2.GoogleCredentials
import utils.*
import Args.Companion.toFlag
import java.io.File

/** A row in the customer config sheet, mapped to respective headers */
typealias CustomerConfig = Map<Config.ConfigSheet.Headers, String?>

/** A strongly-typed, zero-cost abstraction for a Customer name */
@JvmInline value class Customer(val name: String)

/** Config of an android app on the Google Play Store */
data class AndroidApp(val packageName: String, val reviewsReportsBucketUri: String? = null)

/** Config of an iOS app on the Apple App Store */
data class AppleApp(val resourceId: String, val storeCredentials: AppleAppStore.ConnectCredentials)

/**
 * Main app configuration loaded from many sources
 *
 * @author Alexandre Chau <alexandre@pocketcampus.org> Copyright (c) 2023 PocketCampus Sàrl
 */
data class Config(
    val spreadsheet: GoogleSheets.Spreadsheets,
    val googleCredentials: GoogleCredentials,
    val apps: Map<Customer, Pair<Result<AndroidApp>, Result<AppleApp>>>,
) {
    companion object {
        private val logger = getLogger()

        /**
         * Loads all configuration starting from a properties file (default is config.properties in
         * workdir)
         */
        suspend fun load(
            googleSpreadsheetId: String,
            googlePrivateKeyPath: String,
            applePrivateKeysPaths: Set<String>
        ): Config {
            val googleCredentials =
                GoogleCredentials.fromStream(File(googlePrivateKeyPath).inputStream())
                    .createScoped(
                        listOf(
                            // access reviews spreadsheet
                            "https://www.googleapis.com/auth/spreadsheets",
                            // access Google Play Store API
                            "https://www.googleapis.com/auth/androidpublisher",
                            // access Google Cloud Storage for older review reports
                            "https://www.googleapis.com/auth/devstorage.read_only"
                        )
                    )

            val appsConfigSheet = GoogleSheets.Spreadsheets(googleSpreadsheetId, googleCredentials)

            val sheetsConfig = loadSheetsConfig(appsConfigSheet)
            val appsConfig = getAppsConfig(sheetsConfig, applePrivateKeysPaths)

            return Config(appsConfigSheet, googleCredentials, appsConfig)
        }

        /** Loads config from a Google Spreadsheet */
        private suspend fun loadSheetsConfig(
            sheet: GoogleSheets.Spreadsheets
        ): List<CustomerConfig> {
            logger.info { "Fetching apps config from ${sheet.browserUrl}" }

            val rows =
                sheet.values.get("Config").values ?: throw Error("The response config was null")

            val headers = rows.first()
            val requiredHeaders = ConfigSheet.Headers.entries.map { it.name }
            if (!headers.containsAll(requiredHeaders)) {
                throw Error(
                    "The config sheet at ${sheet.browserUrl} does not contain all required config headers. \n\tSheet headers: $headers \n\tRequired headers: $requiredHeaders"
                )
            }

            return rows
                .drop(2) // remove header, description row
                .filter { it.isNotEmpty() } // ignore empty rows
                .map {
                    headers
                        // the rows should have the same length (but Google Sheets API cuts off
                        // trailing empty cells of row so we pad)
                        .zip(it.padEnd(headers.size, ""))
                        // only keep columns that are under required headers
                        .filter { (header, _) -> requiredHeaders.contains(header) }
                        .associate { (header, value) ->
                            ConfigSheet.Headers.valueOf(header) to value
                        }
                }
        }

        /** Compute config per app from customer configs */
        private suspend fun getAppsConfig(
            sheetsConfig: List<CustomerConfig>,
            applePrivateKeysPaths: Set<String>
        ): Map<Customer, Pair<Result<AndroidApp>, Result<AppleApp>>> {
            logger.info {
                "Customers in apps config: ${sheetsConfig.map { it[ConfigSheet.Headers.CustomerName] }}"
            }

            return sheetsConfig.associate { row ->
                val customerName = row[ConfigSheet.Headers.CustomerName]
                if (customerName.isNullOrEmpty()) {
                    throw Error(
                        "Spreadsheet config error: the customer name must be specified! In row $row"
                    )
                }

                val packageName = row[ConfigSheet.Headers.GooglePlayStorePackageName]
                val reportsBucketUri =
                    row[ConfigSheet.Headers.GooglePlayStoreReviewsReportsBucketUri]
                val resourceId = row[ConfigSheet.Headers.AppleAppStoreResourceId]
                val issuerId = row[ConfigSheet.Headers.AppleAppStoreConnectIssuerId]
                val keyId = row[ConfigSheet.Headers.AppleAppStoreKeyId]

                val android = Result.of {
                    if (packageName.isNullOrEmpty()) {
                        throw Error("Spreadsheet config error: Android app packageName not specified")
                    }
                    AndroidApp(packageName, reportsBucketUri.emptyAsNull())
                }

                val apple = Result.of {
                    if (resourceId.isNullOrEmpty()) {
                        throw Error("")
                    }
                    if (keyId.isNullOrEmpty() || issuerId.isNullOrEmpty()) {
                        throw Error(
                            "Spreadsheet config error: the Apple App Store key ID and issuer ID must be specified if the apple resource ID is set ($resourceId)! In row $row"
                        )
                    }
                    val privateKeyPath = applePrivateKeysPaths.find { path -> path.contains(keyId) } ?: throw Error(
                        "No Apple App Store Connect private key path specified for $packageName with resource ID $resourceId (issuer ID: $issuerId, key ID: $keyId from spreadsheet config). Did you provide the corresponding key file path with ${Args::applePrivateKeyPath.toFlag()} and is the file correctly named AuthKey_$keyId.p8 ?"
                    )
                    AppleApp(
                        resourceId, AppleAppStore.ConnectCredentials(privateKeyPath, keyId, issuerId)

                    )
                }

                Customer(customerName) to Pair(android, apple)
            }
        }
    }

    class ConfigSheet {
        enum class Headers {
            CustomerName,
            GooglePlayStorePackageName,
            GooglePlayStoreReviewsReportsBucketUri,
            AppleAppStoreResourceId,
            AppleAppStoreConnectIssuerId,
            AppleAppStoreKeyId,
        }
    }
}
