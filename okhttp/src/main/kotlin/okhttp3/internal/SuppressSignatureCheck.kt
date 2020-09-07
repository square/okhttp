package okhttp3.internal

import java.lang.annotation.Documented
import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.CONSTRUCTOR
import kotlin.annotation.AnnotationTarget.FUNCTION

@Retention(BINARY)
@Documented
@Target(CONSTRUCTOR, CLASS, FUNCTION)
annotation class SuppressSignatureCheck
