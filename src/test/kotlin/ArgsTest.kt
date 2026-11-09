import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for CLI args and environment variable parsing in Args
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2026 PocketCampus Sàrl
 */
class ArgsTest {

  @Test
  fun testSsotDerivation() {
    with(Args) {
      assertEquals("--googleSpreadsheetId", Args::googleSpreadsheetId.toFlag())
      assertEquals("--logAllWarnings", Args::logAllWarnings.toFlag())
      assertEquals("GOOGLE_SPREADSHEET_ID", Args::googleSpreadsheetId.toEnvVar())
      assertEquals("GOOGLE_PRIVATE_KEY_PATH", Args::googlePrivateKeyPath.toEnvVar())
      assertEquals("APPLE_PRIVATE_KEY_PATHS", Args::applePrivateKeyPaths.toEnvVar())
      assertEquals("SLACK_WEBHOOK", Args::slackWebhook.toEnvVar())
      assertEquals("LOG_ALL_WARNINGS", Args::logAllWarnings.toEnvVar())
    }
  }

  @Test
  fun testParseFromEnvironmentVariablesOnly() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet-env-456",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/auth1.p8:/secrets/auth2.p8,/secrets/auth3.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
      "LOG_ALL_WARNINGS" to "false",
    )

    val args = Args.parse(emptyArray(), env)

    assertEquals("sheet-env-456", args.googleSpreadsheetId)
    assertEquals("/secrets/google.json", args.googlePrivateKeyPath)
    assertEquals(
      setOf("/secrets/auth1.p8", "/secrets/auth2.p8", "/secrets/auth3.p8"),
      args.applePrivateKeyPaths
    )
    assertEquals("https://hooks.slack.com/env", args.slackWebhook)
    assertFalse(args.logAllWarnings)
  }

  @Test
  fun testNonSensitiveCliFlagsOverrideEnvVars() {
    val cli = arrayOf(
      "--googleSpreadsheetId", "cli-sheet-id",
      "--logAllWarnings", "true",
    )
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "env-sheet-id",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/auth1.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
      "LOG_ALL_WARNINGS" to "false",
    )

    val args = Args.parse(cli, env)

    assertEquals("cli-sheet-id", args.googleSpreadsheetId)
    assertTrue(args.logAllWarnings)
  }

  @Test
  fun testSensitiveCliArgsAreRejected() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/auth.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    // Passing --googlePrivateKeyPath as CLI flag must be rejected
    val ex1 = assertFailsWith<IllegalArgumentException> {
      Args.parse(arrayOf("--googlePrivateKeyPath", "/secrets/google.json"), env)
    }
    assertTrue(ex1.message!!.contains("Sensitive parameter '--googlePrivateKeyPath' is not accepted"))

    // Passing --applePrivateKeyPaths as CLI flag must be rejected
    val ex2 = assertFailsWith<IllegalArgumentException> {
      Args.parse(arrayOf("--applePrivateKeyPaths", "/secrets/apple.p8"), env)
    }
    assertTrue(ex2.message!!.contains("Sensitive parameter '--applePrivateKeyPaths' is not accepted"))

    // Passing --slackWebhook as CLI flag must be rejected
    val ex3 = assertFailsWith<IllegalArgumentException> {
      Args.parse(arrayOf("--slackWebhook", "https://hooks.slack.com/webhook"), env)
    }
    assertTrue(ex3.message!!.contains("Sensitive parameter '--slackWebhook' is not accepted"))
  }

  @Test
  fun testAlternativeEnvVarNamesAreNotSupported() {
    // Single env var APPLE_PRIVATE_KEY_PATHS must be used, not APPLE_PRIVATE_KEY_PATH
    val env1 = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATH" to "/secrets/single-apple.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )
    assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env1)
    }

    // Single env var SLACK_WEBHOOK must be used, not SLACK_WEBHOOK_URL
    val env2 = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/apple.p8",
      "SLACK_WEBHOOK_URL" to "https://hooks.slack.com/url",
    )
    assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env2)
    }
  }

  @Test
  fun testMissingSpreadsheetIdThrows() {
    val env = mapOf(
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/apple.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env)
    }
    assertTrue(ex.message!!.contains("GOOGLE_SPREADSHEET_ID"))
  }

  @Test
  fun testMissingGoogleKeyThrows() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/apple.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env)
    }
    assertTrue(ex.message!!.contains("GOOGLE_PRIVATE_KEY_PATH"))
  }

  @Test
  fun testMissingAppleKeyThrows() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env)
    }
    assertTrue(ex.message!!.contains("APPLE_PRIVATE_KEY_PATHS"))
  }

  @Test
  fun testMissingSlackWebhookThrows() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/apple.p8",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env)
    }
    assertTrue(ex.message!!.contains("SLACK_WEBHOOK"))
  }

  @Test
  fun testOddNumberOfArgsThrows() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/apple.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(arrayOf("--googleSpreadsheetId"), env)
    }
    assertTrue(ex.message!!.contains("Wrong number of arguments"))
  }

  @Test
  fun testUnknownFlagThrows() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "/secrets/apple.p8",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(arrayOf("--unknownFlag", "value"), env)
    }
    assertTrue(ex.message!!.contains("Wrong argument flag"))
  }

  @Test
  fun testEmptyAppleKeyPathsThrows() {
    val env = mapOf(
      "GOOGLE_SPREADSHEET_ID" to "sheet",
      "GOOGLE_PRIVATE_KEY_PATH" to "/secrets/google.json",
      "APPLE_PRIVATE_KEY_PATHS" to "   ",
      "SLACK_WEBHOOK" to "https://hooks.slack.com/env",
    )

    val ex = assertFailsWith<IllegalArgumentException> {
      Args.parse(emptyArray(), env)
    }
    assertTrue(ex.message!!.contains("Apple private key paths were empty"))
  }
}
