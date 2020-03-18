package okhttp3

import android.annotation.SuppressLint
import okhttp3.internal.platform.OkHttpTrustManagerJvm
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.OkHttpTrustManagerAndroid
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

object OkHttpTrustManager {
  class Builder(var delegate: X509TrustManager) {
    private var overrides: MutableList<Override> = mutableListOf()

    fun delegate(delegate: X509TrustManager) = apply {
      this.delegate = delegate
    }

    fun hostOverride(hostName: String, trustManager: X509TrustManager) = apply {
      this.overrides.add(Override({ it == hostName }, trustManager))
    }

    @IgnoreJRERequirement
    @SuppressLint("NewApi")
    fun build(): X509TrustManager {
      return if (Platform.get().isAndroid) {
        OkHttpTrustManagerAndroid(delegate, overrides.toList())
      } else {
        OkHttpTrustManagerJvm(delegate as X509ExtendedTrustManager, overrides.toList())
      }
    }
  }
}

