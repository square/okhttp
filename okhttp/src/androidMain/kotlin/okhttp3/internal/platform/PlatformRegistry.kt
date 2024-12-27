package okhttp3.internal.platform

import android.content.Context
import okhttp3.internal.platform.android.AndroidLog

actual object PlatformRegistry {
  actual fun findPlatform(): Platform {
    AndroidLog.enable()
    return Android10Platform.buildIfSupported() ?: AndroidPlatform.buildIfSupported()!!
  }

  actual val isAndroid: Boolean
    get() = true

  var applicationContext: Context?
    get() = (Platform.get() as? ContextAwarePlatform)?.applicationContext
    set(value) {
      (Platform.get() as? ContextAwarePlatform)?.applicationContext = value
    }
}
