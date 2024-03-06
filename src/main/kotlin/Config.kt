import apis.AppleAppStore
import apis.GoogleSheets
import com.google.auth.oauth2.GoogleCredentials
import utils.*
import java.io.File

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
    val apps: Map<Customer, Pair<AndroidApp?, AppleApp?>>,
    val slackReviewsWebhook: String,
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
            val appsConfig = loadAppsConfig(sheetsConfig, applePrivateKeysPaths)

            val slackReviewsWebhook = sheetsConfig.firstOrThrow("slackReviewsWebhook")

            return Config(appsConfigSheet, googleCredentials, appsConfig, slackReviewsWebhook)
        }

        /** Loads config from a Google Spreadsheet */
        private suspend fun loadSheetsConfig(sheet: GoogleSheets.Spreadsheets): SheetsConfig {
            logger.info { "Fetching apps config from ${sheet.browserUrl}" }

            val configRows =
                sheet.values.get("Config").values ?: throw Error("The response config was null")

            // ignore empty rows or when config key is not set in first column
            val validRows = configRows.filter { it.isNotEmpty() && it.first().isNotEmpty() }

            val configMap =
                validRows.associate { row ->
                    // first column is key name, second is description, then values
                    row.first() to row.drop(2)
                }

            return SheetsConfig(configMap)
        }

        /** Load apps config from a sheet */
        private fun loadAppsConfig(
            sheetsConfig: SheetsConfig,
            applePrivateKeysPaths: Set<String>
        ): Map<Customer, Pair<AndroidApp?, AppleApp?>> {
            // the following rows should have the same length (but Google Sheets API cuts off empty
            // cells in rows, so we pad)
            val customers = sheetsConfig.getRowOrThrow("customerName")
            val googlePlayStorePackageNames =
                sheetsConfig.getRowOrThrow("googlePlayStorePackageNames").padEnd(customers.size, "")
            val appleAppResourceIds =
                sheetsConfig.getRowOrThrow("appleAppResourceIds").padEnd(customers.size, "")
            val gsReviewsReportsBucketsUris =
                sheetsConfig.getRowOrThrow("gsReviewsReportsBucketsUris").padEnd(customers.size, "")
            val appleAppStoreIssuerIds =
                sheetsConfig.getRowOrThrow("appleAppStoreIssuerIds").padEnd(customers.size, "")
            val appleAppStoreKeyIds =
                sheetsConfig.getRowOrThrow("appleAppStoreKeyIds").padEnd(customers.size, "")

            logger.info { "Customers in apps config: $customers" }

            return zip(
                    customers,
                    googlePlayStorePackageNames,
                    appleAppResourceIds,
                    gsReviewsReportsBucketsUris,
                    appleAppStoreIssuerIds,
                    appleAppStoreKeyIds
                )
                .associate { (name, packageName, resourceId, reportsBucketUri, issuerId, keyId) ->
                    if (name.isEmpty()) {
                        throw Error("Spreadsheet config error: the customer name must be specified! In column containing $packageName, $resourceId, $reportsBucketUri, $issuerId, $keyId")
                    }

                    val android =
                        packageName.emptyAsNull()?.let {
                            AndroidApp(packageName, reportsBucketUri.emptyAsNull())
                        }
                    val apple =
                        resourceId.emptyAsNull()?.let {
                            if (keyId.isEmpty() || issuerId.isEmpty()) {
                                throw Error("Spreadsheet config error: the Apple App Store key ID and issuer ID must be specified if the apple resource ID is set ($resourceId)! In column of customer $name")
                            }
                            val privateKeyPath =
                                applePrivateKeysPaths.find { it.contains(keyId) }
                                    ?: throw Error(
                                        "No Apple App Store Connect private key path specified for $name with resource ID " +
                                            "$resourceId (issuer ID: $issuerId, key ID: $keyId from spreadsheet config). Did you " +
                                            "provide the corresponding key file path with --applePrivateKeyPath and is the file " +
                                            "correctly named AuthKey_<$keyId>.p8 ?"
                                    )
                            AppleApp(
                                resourceId,
                                AppleAppStore.ConnectCredentials(privateKeyPath, keyId, issuerId)
                            )
                        }

                    Customer(name) to Pair(android, apple)
                }
        }

        /**
         * Helper to read config from a Google Spreadsheet Represents a sheet grid as key-values,
         * where the key is a given column and the values any number of columns specified at
         * instantiation
         */
        class SheetsConfig(private val configMap: Map<String, List<String>>) {
            /**
             * Reads the first value cell for a given config row, throws if config key is missing or
             * if value is null or empty
             */
            fun firstOrThrow(key: String) =
                configMap[key]?.first().emptyAsNull()
                    ?: throw Error("Config with key $key is not defined")

            /**
             * Reads the rest of the config row (i.e. all values cells) as value list, throws if
             * config key is missing
             */
            fun getRowOrThrow(key: String) =
                configMap[key] ?: throw Error("Config with key $key is not defined")
        }
    }
}
