package okhttp3.internal.platform

import okhttp3.internal.platform.android.AndroidLog

actual object PlatformRegistry {
  actual fun findPlatform(): Platform {
    AndroidLog.enable()
    return Android10Platform.buildIfSupported() ?: AndroidPlatform.buildIfSupported()!!
  }

  actual val isAndroid: Boolean
    get() = true
}
