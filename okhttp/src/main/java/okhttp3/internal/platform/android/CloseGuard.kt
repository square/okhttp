package okhttp3.internal.platform.android

import java.lang.reflect.Method

/**
 * Provides access to the internal dalvik.system.CloseGuard class. Android uses this in
 * combination with android.os.StrictMode to report on leaked java.io.Closeable's. Available since
 * Android API 11.
 */
internal class CloseGuard(
  private val getMethod: Method?,
  private val openMethod: Method?,
  private val warnIfOpenMethod: Method?
) {

  fun createAndOpen(closer: String): Any? {
    if (getMethod != null) {
      try {
        val closeGuardInstance = getMethod.invoke(null)
        openMethod!!.invoke(closeGuardInstance, closer)
        return closeGuardInstance
      } catch (_: Exception) {
      }
    }
    return null
  }

  fun warnIfOpen(closeGuardInstance: Any?): Boolean {
    var reported = false
    if (closeGuardInstance != null) {
      try {
        warnIfOpenMethod!!.invoke(closeGuardInstance)
        reported = true
      } catch (_: Exception) {
      }
    }
    return reported
  }

  companion object {
    fun get(): CloseGuard {
      var getMethod: Method?
      var openMethod: Method?
      var warnIfOpenMethod: Method?

      try {
        val closeGuardClass = Class.forName("dalvik.system.CloseGuard")
        getMethod = closeGuardClass.getMethod("get")
        openMethod = closeGuardClass.getMethod("open", String::class.java)
        warnIfOpenMethod = closeGuardClass.getMethod("warnIfOpen")
      } catch (_: Exception) {
        getMethod = null
        openMethod = null
        warnIfOpenMethod = null
      }

      return CloseGuard(getMethod, openMethod, warnIfOpenMethod)
    }
  }
}