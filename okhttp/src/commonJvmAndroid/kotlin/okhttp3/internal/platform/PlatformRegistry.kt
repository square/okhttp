package okhttp3.internal.platform

expect object PlatformRegistry {
  fun findPlatform(): Platform

  val isAndroid: Boolean
}
