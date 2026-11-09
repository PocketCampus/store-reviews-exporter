import kotlin.reflect.KProperty
import kotlin.reflect.full.memberProperties

/**
 * Describes the main CLI arguments
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */
data class Args(
  val googleSpreadsheetId: String,
  val googlePrivateKeyPath: String,
  val applePrivateKeyPaths: Set<String>,
  val slackWebhook: String,
  val logAllWarnings: Boolean,
) {
  companion object {
    /**
     * Reflection-based extension function to stringify an argument name as a CLI flag (--camelCase)
     */
    fun <T> KProperty<T>.toFlag(): String = "--${this.name}"

    /**
     * Reflection-based extension function to convert a camelCase property name to SCREAMING_SNAKE_CASE
     * for its corresponding environment variable.
     */
    fun <T> KProperty<T>.toEnvVar(): String =
      this.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

    /**
     * Properties accepted as CLI flags. Sensitive properties are intentionally excluded.
     */
    val cliProperties = setOf(
      Args::googleSpreadsheetId,
      Args::logAllWarnings,
    )

    /**
     * Sensitive credential properties that must only be supplied via environment variables.
     */
    val sensitiveProperties = setOf(
      Args::googlePrivateKeyPath,
      Args::applePrivateKeyPaths,
      Args::slackWebhook,
    )

    private val allowedFlags = cliProperties.map { it.toFlag() }.toSet()
    private val rejectedSensitiveFlags = sensitiveProperties.map { it.toFlag() }.toSet()
    private val sensitiveEnvVarNames = sensitiveProperties.map { it.toEnvVar() }

    /**
     * Factory to create the args object from CLI args and environment variables.
     * Sensitive credentials must be injected via environment variables.
     */
    fun parse(
      args: Array<String> = emptyArray(),
      env: Map<String, String> = System.getenv(),
    ): Args {
      val helpMessage =
        "Available CLI flags: $allowedFlags. Sensitive credentials must be passed via environment variables (${sensitiveEnvVarNames.joinToString(", ")})."

      require(args.size % 2 == 0) {
        "Wrong number of arguments! The arguments list should be a list of pairs <--flag> <value>. $helpMessage, current arguments: ${args.toList()}"
      }
      val options = args.withIndex().mapNotNull { (index, value) ->
        val isFlag = allowedFlags.contains(value)
        if (index % 2 == 0) {
          // every even index (starting at 0) argument should be a flag name
          if (!isFlag) {
            if (value in rejectedSensitiveFlags) {
              val expectedEnv = value.removePrefix("--").replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
              throw IllegalArgumentException("Sensitive parameter '$value' is not accepted as a CLI argument. Pass it via environment variable $expectedEnv instead. $helpMessage")
            }
            throw IllegalArgumentException("Wrong argument flag at index $index: $value. Should be one of $allowedFlags. $helpMessage")
          }
          null // return pairs from the values only
        } else {
          // every odd index (starting at 0) should be a value
          if (isFlag) {
            throw IllegalArgumentException(
              "Wrong argument value at index $index: $value: cannot be a flag name $allowedFlags. Did you forget to follow an argument flag by its value? $helpMessage"
            )
          }
          args[index - 1] to value // args[index - 1] was checked to exist and be a flag at prev iteration
        }
      }.groupBy({ (key, _) -> key }, { (_, value) -> value }) // group all passed values by flag

      val googleSpreadsheetId = options[Args::googleSpreadsheetId.toFlag()]?.lastOrNull()
        ?: env[Args::googleSpreadsheetId.toEnvVar()]
        ?: throw IllegalArgumentException("Spreadsheet ID was not specified (via ${Args::googleSpreadsheetId.toFlag()} flag or ${Args::googleSpreadsheetId.toEnvVar()} environment variable)")

      val googlePrivateKeyPath = env[Args::googlePrivateKeyPath.toEnvVar()]
        ?: throw IllegalArgumentException("Google private key path was not specified (via ${Args::googlePrivateKeyPath.toEnvVar()} environment variable)")

      val applePrivateKeyPathsRaw = env[Args::applePrivateKeyPaths.toEnvVar()]
        ?: throw IllegalArgumentException("Apple private key paths were not specified (via ${Args::applePrivateKeyPaths.toEnvVar()} environment variable)")
      val applePrivateKeyPaths = applePrivateKeyPathsRaw.split(Regex("[:,\n]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
      require(applePrivateKeyPaths.isNotEmpty()) {
        "Apple private key paths were empty in ${Args::applePrivateKeyPaths.toEnvVar()} environment variable"
      }

      val slackWebhook = env[Args::slackWebhook.toEnvVar()]
        ?: throw IllegalArgumentException("Slack webhook URL was not specified (via ${Args::slackWebhook.toEnvVar()} environment variable)")

      val logAllWarnings = (options[Args::logAllWarnings.toFlag()]?.lastOrNull()
        ?: env[Args::logAllWarnings.toEnvVar()])?.toBoolean() ?: false

      return Args(
        googleSpreadsheetId = googleSpreadsheetId,
        googlePrivateKeyPath = googlePrivateKeyPath,
        applePrivateKeyPaths = applePrivateKeyPaths,
        slackWebhook = slackWebhook,
        logAllWarnings = logAllWarnings,
      )
    }
  }
}