package utils

/**
 * @author Alexandre Chau <alexandre@pocketcampus.org>
 *         Copyright (c) 2024 PocketCampus Sàrl
 */
class Logger {
    /**
     * Formats a message to prepend the call site file name and line number
     * ⚠️sensitive to the call location: since this method inspects the stack trace to display the call site, this
     *   method expects to be called from within the first scope of the body of another method of this class.
     *   Otherwise, the wrong call site will be displayed (in other words stackTrace[3] is the call site of logger.foo()
     *   if and only if format() is called directly in the body of foo(), and NOT from a nested function call).
     *   Otherwise, you must specify the call depth (0-indexed)
     *   Examples:
     *      ✅ DO:       class Logger { fun foo() { format(s) } }
     *      ✅ DO:       class Logger { fun foo() { bar() }; fun bar() { format(s, 1) } }
     *      ❌ DON'T:    class Logger { fun foo() { bar() }; fun bar() { format(s) } }
     */
    private fun format(message: String, callDepth: Int = 0): String {
        val callSite = Thread.currentThread().stackTrace.getOrNull(3 + callDepth)
        return if (callSite == null) message
        else "[${callSite.fileName}:${callSite.lineNumber}] $message\n"
    }

    private fun info(message: String, callDepth: Int = 0) {
        println(format(message, callDepth))
    }

    fun info(message: () -> String) = info(message(), 1)

    fun info(vararg messages: String) {
        messages.forEach { info(it, 1) }
    }

    private fun error(message: String, callDepth: Int = 0) {
        System.err.println(format(message, callDepth))
    }

    fun error(message: () -> String) = error(message(), 1)

    fun error(vararg messages: String) {
        messages.forEach { error(it, 1) }
    }
}

fun getLogger() = Logger()