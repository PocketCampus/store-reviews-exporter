import apis.AppleAppStore
import apis.GoogleSheets
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.emptyAsNull
import utils.getLogger
import utils.padEnd
import java.io.File
import java.util.*

/**
 * A strongly-typed, zero-cost abstraction for a Customer name
 */
@JvmInline
value class Customer(val name: String)

/**
 * Represents the config of an android app and its metadata
 */
data class AndroidApp(val packageName: String, val reviewsReportsBucketUri: String? = null)

/**
 * A strongly-typed, zero-cost abstraction for an apple app name
 */
@JvmInline
value class AppleApp(val resourceId: String)

/**
 * Main app configuration loaded from many sources
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
data class Config(
    val spreadsheet: GoogleSheets.Spreadsheets,
    val googleCredentials: GoogleCredentials,
    val appleCredentials: AppleAppStore.ConnectCredentials,
    val apps: Map<Customer, Pair<AndroidApp?, AppleApp?>>,
    val slackReviewsWebhook: String,
) {
    companion object {
        private val logger = getLogger()

        /**
         * Convenience extension function that allows retrieve a property from the config or else throw (resulting type is non-nullable)
         */
        private fun Properties.getOrThrow(key: String): String =
            this.getProperty(key) ?: run { throw Error("Key $key is not defined in $this.getPath!") }

        /**
         * Loads all configuration starting from a properties file (default is config.properties in workdir)
         */
        suspend fun load(configPath: String = "config.properties"): Config {
            val properties = Properties()

            // load credentials config from local file
            val file = File(configPath)

            logger.info { "Loading config from ${file.absolutePath}" }
            withContext(Dispatchers.IO) {
                properties.load(file.bufferedReader())
            }

            val appleKeyId = properties.getOrThrow("APPLE_APP_STORE_KEY_ID")
            val applePrivateKeyPath = properties.getOrThrow("APPLE_APP_STORE_PRIVATE_KEY_PATH")
            val appleIssuerId = properties.getOrThrow("APPLE_APP_STORE_ISSUER_ID")
            val appleCredentials = AppleAppStore.ConnectCredentials(applePrivateKeyPath, appleKeyId, appleIssuerId)

            val googlePrivateKeyPath = properties.getOrThrow("GOOGLE_PLAY_STORE_PRIVATE_KEY_PATH")
            val googleCredentials = GoogleCredentials.fromStream(File(googlePrivateKeyPath).inputStream()).createScoped(
                listOf(
                    "https://www.googleapis.com/auth/spreadsheets",
                    "https://www.googleapis.com/auth/androidpublisher",
                    "https://www.googleapis.com/auth/devstorage.read_only"
                )
            )

            val appsConfigSheetId = properties.getOrThrow("APPS_CONFIG_GOOGLE_SHEET_ID")
            val appsConfigSheet = GoogleSheets.Spreadsheets(
                appsConfigSheetId, googleCredentials
            )

            val sheetsConfig = loadSheetsConfig(appsConfigSheet)
            val appsConfig = loadAppsConfig(sheetsConfig)

            val slackReviewsWebhook = sheetsConfig.firstOrThrow("slackReviewsWebhook")

            return Config(appsConfigSheet, googleCredentials, appleCredentials, appsConfig, slackReviewsWebhook)
        }

        /**
         * Loads config from a Google Spreadsheet
         */
        private suspend fun loadSheetsConfig(sheet: GoogleSheets.Spreadsheets): SheetsConfig {
            logger.info { "Fetching apps config from ${sheet.browserUrl}" }

            val configRows = sheet.values.get("Config").values ?: throw Error("The response config was null")

            // ignore empty rows or when config key is not set in first column
            val validRows = configRows.filter { it.isNotEmpty() && it.first().isNotEmpty() }

            val configMap = validRows.associate { row ->
                // first column is key name, second is description, then values
                row.first() to row.drop(2)
            }

            return SheetsConfig(configMap)
        }

        /**
         * Load apps config from a sheet
         */
        private fun loadAppsConfig(sheetsConfig: SheetsConfig): Map<Customer, Pair<AndroidApp?, AppleApp?>> {
            // the following rows should have the same length (but Google Sheets API cuts off empty cells in rows, so we pad)
            val customers = sheetsConfig.getRowOrThrow("customerName")
            val googlePlayStorePackageNames =
                sheetsConfig.getRowOrThrow("googlePlayStorePackageNames").padEnd(customers.size, "")
            val appleAppResourceIds = sheetsConfig.getRowOrThrow("appleAppResourceIds").padEnd(customers.size, "")
            val gsReviewsReportsBucketsUris =
                sheetsConfig.getRowOrThrow("gsReviewsReportsBucketsUris").padEnd(customers.size, "")

            logger.info { "Customers in apps config: $customers" }

            return customers.zip(googlePlayStorePackageNames.zip(appleAppResourceIds).zip(gsReviewsReportsBucketsUris))
                .associate { (name, config) ->
                    val (appIds, reportsBucketUri) = config
                    val (packageName, resourceId) = appIds
                    Customer(name) to Pair(packageName.emptyAsNull()?.let {
                        AndroidApp(packageName, reportsBucketUri.emptyAsNull())
                    }, resourceId.emptyAsNull()?.let {
                        AppleApp(resourceId)
                    })
                }
        }

        /**
         * Helper to read config from a Google Spreadsheet
         * Represents a sheet grid as key-values, where the key is a given column and the values any number of columns specified at instantiation
         */
        class SheetsConfig(private val configMap: Map<String, List<String>>) {
            /**
             * Reads the first value cell for a given config row, throws if config key is missing or if value is null or empty
             */
            fun firstOrThrow(key: String) =
                configMap[key]?.first().emptyAsNull() ?: throw Error("Config with key $key is not defined")

            /**
             * Reads the rest of the config row (i.e. all values cells) as value list, throws if config key is missing
             */
            fun getRowOrThrow(key: String) = configMap[key] ?: throw Error("Config with key $key is not defined")
        }
    }
}