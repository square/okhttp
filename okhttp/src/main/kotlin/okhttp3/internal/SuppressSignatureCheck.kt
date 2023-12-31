package okhttp3.internal

import java.lang.annotation.Documented
import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.*

@Retention(BINARY)
@Documented
@Target(CONSTRUCTOR, CLASS, FUNCTION, PROPERTY)
internal annotation class SuppressSignatureCheck
