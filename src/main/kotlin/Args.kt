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
  val applePrivateKeyPath: Set<String>,
  val slackWebhook: String,
  val logAllWarnings: Boolean,
) {
  companion object {
    /**
     * Reflection-based extension function to stringify an argument name as a flag
     */
    fun <T> KProperty<T>.toFlag(): String = "--${this.name}"

    /**
     * Returns the available flags of the programs, derived by reflection from the properties
     */
    private fun getAvailableFlags() = Args::class.memberProperties.map { it.toFlag() }

    /**
     * Factory to create the args object from the CLI args
     */
    fun parse(args: Array<String>): Args {
      val flags = getAvailableFlags()
      val helpMessage =
        "The arguments list should be a list of pairs <--flag> <value>, available flags: $flags, current arguments: ${args.toList()}"

      if (args.size % 2 != 0) {
        throw Error("Wrong number of arguments! $helpMessage")
      }
      val options = args.withIndex().mapNotNull { (index, value) ->
        val isFlag = flags.contains(value)
        if (index % 2 == 0) {
          // every even index (starting at 0) argument should be a flag name
          if (!isFlag) {
            throw Error("Wrong argument flag at index ${index}: ${value}. Should be one of ${flags}. $helpMessage")
          }
          null // return pairs from the values only
        } else {
          // every odd index (starting at 0) should be a value
          if (isFlag) {
            throw Error(
              "Wrong argument value at index ${index}: ${value}: cannot be a flag name ${flags}. Did you forget to follow an argument flag by its value? $helpMessage"
            )
          }
          args[index - 1] to value // args[index - 1] was checked to exist and be a flag at prev iteration
        }
      }.groupBy({ (key, _) -> key }, { (_, value) -> value }) // group all passed values by flag

      /**
       * Returns the list of argument values for a given flag, or else null
       */
      fun <V> Map<String, V>.valueListOrNull(prop: KProperty<Any>) =
        this[prop.toFlag()]

      /*
        Returns the list of argument values for a given flag, or else throws
       */
      fun <V> Map<String, V>.valueListOrThrow(prop: KProperty<Any>) =
        valueListOrNull(prop) ?: throw Error("Argument ${prop.toFlag()} was not specified")
        
      // for single value args, we usually want the last
      return Args(
        options.valueListOrThrow(Args::googleSpreadsheetId).last(),
        options.valueListOrThrow(Args::googlePrivateKeyPath).last(),
        options.valueListOrThrow(Args::applePrivateKeyPath).toSet(),
        options.valueListOrThrow(Args::slackWebhook).last(),
        options.valueListOrNull(Args::logAllWarnings)?.last()?.toBoolean() ?: false,
      )
    }
  }
}