package apis

import io.jsonwebtoken.JwtBuilder
import io.jsonwebtoken.Jwts
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import kotlin.time.Duration.Companion.minutes

/**
 * A Kotlin representation of the Apple App Store reviews API
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2023 PocketCampus Sàrl
 */
interface AppleAppStore {
    /**
     * An implementation for Apple App Store Connect API keys using signed JWT
     * https://developer.apple.com/documentation/appstoreconnectapi/creating_api_keys_for_app_store_connect_api
     */
    class ConnectCredentials(privateKeyPath: String, val keyId: String, val issuerId: String) {
        private val privateKey: PrivateKey
        private var tokenCache: Token? = null

        init {
            val keyFileContent = File(privateKeyPath).readText(Charsets.UTF_8)
            val keyString = keyFileContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .trim()

            val decodedKeyBytes = Base64.getDecoder().decode(keyString)
            val keySpec = PKCS8EncodedKeySpec(decodedKeyBytes)

            val factory = KeyFactory.getInstance("EC")
            privateKey = factory.generatePrivate(keySpec)
        }

        /**
         * https://developer.apple.com/documentation/appstoreconnectapi/creating_api_keys_for_app_store_connect_api#3028608
         */
        fun getOrRefreshToken(): String {
            val now = System.now()
            val currentToken = tokenCache
            if (currentToken != null && now < currentToken.expiration) {
                return currentToken.value
            }

            val expiration = now.plus(20.minutes)
            val token = Jwts.builder()
                .header()
                    .add("alg", "ES256")
                    .add("kid", keyId)
                    .add("typ", "JWT")
                    .and()
                .issuer(issuerId)
                .issuedAt(now)
                .expiration(expiration)
                .audience()
                    .add("appstoreconnect-v1")
                    .and()
                .signWith(privateKey)
                .compact()

            tokenCache = Token(expiration, token)
            return token
        }

        /**
         * Helper extension method to convert date format
         */
        private fun JwtBuilder.issuedAt(instant: Instant) = this.issuedAt(Date.from(instant.toJavaInstant()))


        /**
         * Helper extension method to convert date format
         */
        private fun JwtBuilder.expiration(instant: Instant) = this.expiration(Date.from(instant.toJavaInstant()))


        /**
         * Helper class to hold the last generated token
         */
        private data class Token(val expiration: Instant, val value: String)
    }

    /**
     * A coroutine-friendly App Store Connect API client implementation using Ktor client
     */
    class Client(val credentials: ConnectCredentials) {
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

        /**
         * https://developer.apple.com/documentation/appstoreconnectapi/list_all_customer_reviews_for_an_app
         */
        suspend fun getCustomerReviews(resourceId: String, paginationLink: String? = null) = client.get(
            paginationLink ?: "https://api.appstoreconnect.apple.com/v1/apps/$resourceId/customerReviews"
        ) {
            headers {
                append(
                    HttpHeaders.Authorization, "Bearer ${credentials.getOrRefreshToken()}"
                )
            }
            if (paginationLink == null) {
                // parameters should already be set on pagination link if it exists
                parameter("sort", "-createdDate")
                parameter("limit", 200)
            }
        }.body<CustomerReviewsResponse>()
    }

    /**
     * https://developer.apple.com/documentation/appstoreconnectapi/customerreviewsresponse
     */
    @Serializable
    data class CustomerReviewsResponse(
        val data: List<CustomerReview>,
        val links: PagedDocumentLinks,
        val meta: PagingInformation,
    )

    /**
     * https://developer.apple.com/documentation/appstoreconnectapi/customerreview
     */
    @Serializable
    data class CustomerReview(
        val attributes: Attributes,
        val id: String,
        val links: ResourceLinks,
        val relationships: Relationships,
        val type: String,
    ) {
        /**
         * https://developer.apple.com/documentation/appstoreconnectapi/customerreview/attributes
         */
        @Serializable
        data class Attributes(
            val body: String,
            val createdDate: Instant,
            val rating: Int,
            val reviewerNickname: String,
            val title: String,
            val territory: TerritoryCode,
        )

        /**
         * https://developer.apple.com/documentation/appstoreconnectapi/customerreview/relationships
         */
        @Serializable
        data class Relationships(val response: Response) {
            /**
             * https://developer.apple.com/documentation/appstoreconnectapi/customerreview/relationships/response
             */
            @Serializable
            data class Response(val data: Data? = null, val links: Links) {
                /**
                 * https://developer.apple.com/documentation/appstoreconnectapi/customerreview/relationships/response/data
                 */
                @Serializable
                data class Data(val id: String, val type: String)

                /**
                 * https://developer.apple.com/documentation/appstoreconnectapi/customerreview/relationships/response/links
                 */
                @Serializable
                data class Links(val related: String, val self: String)
            }
        }
    }


    /**
     * https://developer.apple.com/documentation/appstoreconnectapi/resourcelinks
     */
    @Serializable
    data class ResourceLinks(val self: String)

    /**
     * https://developer.apple.com/documentation/appstoreconnectapi/pageddocumentlinks
     */
    @Serializable
    data class PagedDocumentLinks(val first: String? = null, val next: String? = null, val self: String)

    /**
     * https://developer.apple.com/documentation/appstoreconnectapi/paginginformation
     */
    @Serializable
    data class PagingInformation(val paging: Paging) {
        /**
         * https://developer.apple.com/documentation/appstoreconnectapi/paginginformation/paging
         */
        @Serializable
        data class Paging(val total: Int, val limit: Int)
    }

    /**
     * https://developer.apple.com/documentation/appstoreconnectapi/territorycode
     */
    @Serializable
    enum class TerritoryCode {
        ABW,
        AFG,
        AGO,
        AIA,
        ALB,
        AND,
        ANT,
        ARE,
        ARG,
        ARM,
        ASM,
        ATG,
        AUS,
        AUT,
        AZE,
        BDI,
        BEL,
        BEN,
        BES,
        BFA,
        BGD,
        BGR,
        BHR,
        BHS,
        BIH,
        BLR,
        BLZ,
        BMU,
        BOL,
        BRA,
        BRB,
        BRN,
        BTN,
        BWA,
        CAF,
        CAN,
        CHE,
        CHL,
        CHN,
        CIV,
        CMR,
        COD,
        COG,
        COK,
        COL,
        COM,
        CPV,
        CRI,
        CUB,
        CUW,
        CXR,
        CYM,
        CYP,
        CZE,
        DEU,
        DJI,
        DMA,
        DNK,
        DOM,
        DZA,
        ECU,
        EGY,
        ERI,
        ESP,
        EST,
        ETH,
        FIN,
        FJI,
        FLK,
        FRA,
        FRO,
        FSM,
        GAB,
        GBR,
        GEO,
        GGY,
        GHA,
        GIB,
        GIN,
        GLP,
        GMB,
        GNB,
        GNQ,
        GRC,
        GRD,
        GRL,
        GTM,
        GUF,
        GUM,
        GUY,
        HKG,
        HND,
        HRV,
        HTI,
        HUN,
        IDN,
        IMN,
        IND,
        IRL,
        IRQ,
        ISL,
        ISR,
        ITA,
        JAM,
        JEY,
        JOR,
        JPN,
        KAZ,
        KEN,
        KGZ,
        KHM,
        KIR,
        KNA,
        KOR,
        KWT,
        LAO,
        LBN,
        LBR,
        LBY,
        LCA,
        LIE,
        LKA,
        LSO,
        LTU,
        LUX,
        LVA,
        MAC,
        MAR,
        MCO,
        MDA,
        MDG,
        MDV,
        MEX,
        MHL,
        MKD,
        MLI,
        MLT,
        MMR,
        MNE,
        MNG,
        MNP,
        MOZ,
        MRT,
        MSR,
        MTQ,
        MUS,
        MWI,
        MYS,
        MYT,
        NAM,
        NCL,
        NER,
        NFK,
        NGA,
        NIC,
        NIU,
        NLD,
        NOR,
        NPL,
        NRU,
        NZL,
        OMN,
        PAK,
        PAN,
        PER,
        PHL,
        PLW,
        PNG,
        POL,
        PRI,
        PRT,
        PRY,
        PSE,
        PYF,
        QAT,
        REU,
        ROU,
        RUS,
        RWA,
        SAU,
        SEN,
        SGP,
        SHN,
        SLB,
        SLE,
        SLV,
        SMR,
        SOM,
        SPM,
        SRB,
        SSD,
        STP,
        SUR,
        SVK,
        SVN,
        SWE,
        SWZ,
        SXM,
        SYC,
        TCA,
        TCD,
        TGO,
        THA,
        TJK,
        TKM,
        TLS,
        TON,
        TTO,
        TUN,
        TUR,
        TUV,
        TWN,
        TZA,
        UGA,
        UKR,
        UMI,
        URY,
        USA,
        UZB,
        VAT,
        VCT,
        VEN,
        VGB,
        VIR,
        VNM,
        VUT,
        WLF,
        WSM,
        YEM,
        ZAF,
        ZMB,
        ZWE
    }
}

/**
 * Convenience extension function to retrieve all customer reviews
 */
suspend fun AppleAppStore.Client.getAllCustomerReviews(
    resourceId: String,
    paginationLink: String? = null,
): List<AppleAppStore.CustomerReview> = getCustomerReviewsWhile(resourceId, paginationLink)

/**
 * Convenience extension function to minimize the number of requests given an objective
 * If the objective is not specified, all reviews will be fetched
 */
suspend fun AppleAppStore.Client.getCustomerReviewsWhile(
    resourceId: String,
    paginationLink: String? = null,
    objective: ((AppleAppStore.CustomerReviewsResponse) -> Boolean)? = null,
): List<AppleAppStore.CustomerReview> {
    val current = getCustomerReviews(resourceId, paginationLink)
    return if (current.links.next == null || (objective != null && !objective(current))) {
        current.data
    } else {
        current.data + getCustomerReviewsWhile(resourceId, current.links.next, objective)
    }
}