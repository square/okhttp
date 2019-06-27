package okhttp3.testing

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Flaky(val issues: Array<String> = [])
