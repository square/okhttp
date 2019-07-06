package okhttp3.testing

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
/**
 * Annotation marking a test as flaky, and requires extra logging and linking against
 * a known github issue.  This does not ignore the failure.
 */
annotation class Flaky(val issues: Array<String> = [])
