/**
 * Determines which errors should be reported as warnings
 */
fun isWarning(throwable: Throwable): Boolean {
  return throwable is MissingGCSBucketError
}