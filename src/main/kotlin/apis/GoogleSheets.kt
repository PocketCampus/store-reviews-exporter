package apis

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal API for Google Spreadsheets using Ktor HTTP client
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
interface GoogleSheets {
    /**
     * A coroutine-friendly Spreadsheet client implementation
     */
    class Spreadsheets(val spreadsheetId: String, val credentials: GoogleCredentials) {
        private val client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(Logging) {
                logger = Logger.SIMPLE
                level = LogLevel.BODY
            }
        }

        val baseUrl = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId"
        val browserUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId"

        val values = Values(client, baseUrl, credentials)

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/get
         */
        suspend fun get(): Spreadsheet {
            credentials.refreshIfExpired()
            return client.get(baseUrl) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${credentials.accessToken.tokenValue}")
                    append(HttpHeaders.Accept, ContentType.Application.Json)
                }
            }.body<Spreadsheet>()
        }

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/batchUpdate
         */
        suspend fun batchUpdate(updateRequest: BatchUpdateRequest): BatchUpdateResponse {
            credentials.refreshIfExpired()
            return client.post("$baseUrl:batchUpdate") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${credentials.accessToken.tokenValue}")
                    append(HttpHeaders.Accept, ContentType.Application.Json)
                    append(HttpHeaders.ContentType, ContentType.Application.Json)
                }
                setBody(updateRequest)
            }.body<BatchUpdateResponse>()
        }

        /**
         * Helper method to add a sheet
         */
        suspend fun addSheet(title: String) = this.batchUpdate(
            BatchUpdateRequest(
                listOf(
                    Request(
                        AddSheetRequest(
                            Sheet.SheetProperties(title)
                        )
                    )
                )
            )
        )

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/batchUpdate#request-body
         */
        @Serializable
        data class BatchUpdateRequest(
            val requests: List<Request>,
            val includeSpreadsheetInResponse: Boolean? = null,
            val responseRanges: List<String>? = null,
            val responseIncludeGridData: Boolean? = null,
        )

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/request#Request
         */
        @Serializable
        data class Request(val addSheet: AddSheetRequest? = null)

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/request#AddSheetRequest
         */
        @Serializable
        data class AddSheetRequest(val properties: Sheet.SheetProperties)

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/batchUpdate#response-body
         */
        @Serializable
        data class BatchUpdateResponse(
            val spreadsheetId: String,
            val replies: List<Response>,
            val updatedSpreadsheet: Spreadsheet? = null,
        )

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/response#Response
         */
        @Serializable
        data class Response(val addSheet: AddSheetResponse? = null)

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/response#AddSheetResponse
         */
        @Serializable
        data class AddSheetResponse(val properties: Sheet.SheetProperties)

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values
         */
        class Values(
            private val client: HttpClient,
            private val baseUrl: String,
            private val credentials: GoogleCredentials,
        ) {
            /**
             * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values/get
             */
            suspend fun get(range: String, dimension: Dimension? = null): ValueRange {
                credentials.refreshIfExpired()
                return client.get("$baseUrl/values/${range}") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${credentials.accessToken.tokenValue}")
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                    }
                    dimension?.let { parameter("majorDimension", dimension.toString()) }
                }.body<ValueRange>()
            }

            /**
             * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values/append
             */
            suspend fun append(
                values: ValueRange,
                valueInputOption: ValueInputOption = ValueInputOption.USER_ENTERED,
                insertDataOption: InsertDataOption = InsertDataOption.INSERT_ROWS,
            ): AppendResponse {
                credentials.refreshIfExpired()
                return client.post("$baseUrl/values/${values.range}:append") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${credentials.accessToken.tokenValue}")
                        append(HttpHeaders.Accept, ContentType.Application.Json)
                        append(HttpHeaders.ContentType, ContentType.Application.Json)
                    }
                    parameter("valueInputOption", valueInputOption)
                    parameter("insertDataOption", insertDataOption)
                    setBody(values)
                }.body<AppendResponse>()
            }

            /**
             * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values#ValueRange
             */
            @Serializable
            data class ValueRange(
                val range: String,
                val values: List<List<String>>? = null,
                val majorDimension: Dimension? = null,
            )

            /**
             * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values/append#response-body
             */
            @Serializable
            data class AppendResponse(
                val spreadsheetId: String,
                val tableRange: String? = null,
                val updates: UpdateValuesResponse,
            )

            /**
             * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets.values/append#InsertDataOption
             */
            @Serializable
            enum class InsertDataOption {
                /**
                 * The new data overwrites existing data in the areas it is written. (Note: adding data to the end of
                 * the sheet will still insert new rows or columns so the data can be written.)
                 */
                OVERWRITE,

                /**
                 * Rows are inserted for the new data.
                 */
                INSERT_ROWS,
            }
        }

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets
         */
        @Serializable
        data class Spreadsheet(
            val spreadsheetId: String,
            val sheets: List<Sheet>,
        )

        /**
         * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/sheets#SheetProperties
         */
        @Serializable
        data class Sheet(
            val properties: SheetProperties,
        ) {
            /**
             * https://developers.google.com/sheets/api/reference/rest/v4/spreadsheets/sheets#sheetproperties
             */
            @Serializable
            data class SheetProperties(val title: String, val sheetId: Int? = null, val index: Int? = null)
        }
    }

    /**
     * https://developers.google.com/sheets/api/reference/rest/v4/ValueInputOption
     */
    enum class ValueInputOption {
        @Deprecated("Default input value. This value must not be used.")
        INPUT_VALUE_OPTION_UNSPECIFIED,

        /**
         * The values the user has entered will not be parsed and will be stored as-is.
         */
        RAW,

        /**
         * The values will be parsed as if the user typed them into the UI. Numbers will stay as numbers, but
         * strings may be converted to numbers, dates, etc. following the same rules that are applied when entering
         * text into a cell via the Google Sheets UI.
         */
        USER_ENTERED
    }

    /**
     * https://developers.google.com/sheets/api/reference/rest/v4/Dimension
     */
    enum class Dimension {
        @Deprecated("The default value, do not use.")
        DIMENSION_UNSPECIFIED,

        /**
         * Operates on the rows of a sheet
         */
        ROWS,

        /**
         * Operates on the columns of a sheet
         */
        COLUMNS,
    }

    /**
     * https://developers.google.com/sheets/api/reference/rest/v4/UpdateValuesResponse
     */
    @Serializable
    data class UpdateValuesResponse(
        val spreadsheetId: String,
        val updatedRange: String,
        val updatedRows: Int? = null,
        val updatedColumns: Int? = null,
        val updatedCells: Int? = null,
        val updatedData: Spreadsheets.Values.ValueRange? = null,
    )
}