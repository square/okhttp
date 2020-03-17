package okhttp3

import android.annotation.SuppressLint
import okhttp3.internal.platform.OkHttpTrustManagerJvm
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.OkHttpTrustManagerAndroid
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

interface OkHttpTrustManager : X509TrustManager {
  companion object {
    @IgnoreJRERequirement
    @SuppressLint("NewApi")
    fun hostOverride(
      delegate: X509TrustManager,
      host: String,
      overrideTrustManager: X509TrustManager
    ): OkHttpTrustManager {
      val override: (String) -> X509TrustManager? = { if (it == host) overrideTrustManager else null }

      return if (Platform.get().isAndroid) {
        OkHttpTrustManagerAndroid(delegate, listOf(override))
      } else {
        OkHttpTrustManagerJvm(delegate as X509ExtendedTrustManager, listOf(override))
      }
    }
  }
}

