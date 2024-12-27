package okhttp3.internal.platform

import android.content.Context

interface ContextAwarePlatform {
  var applicationContext: Context?
}
