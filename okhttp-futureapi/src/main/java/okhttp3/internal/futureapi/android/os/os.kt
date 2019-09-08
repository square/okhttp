package okhttp3.internal.futureapi.android.os

import android.os.Build
import okhttp3.internal.futureapi.android.util.isAndroid

object BuildX {
  val VERSION_SDK_INT: Int = if (isAndroid) { Build.VERSION.SDK_INT } else { -1 }
}