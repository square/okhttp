package okhttp3.android

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.BuildConfig
import okhttp3.internal.tls.AllowlistedTrustManager

const val MiB = 1024L * 1024L

fun setDevMode(buildConfigClass: Class<*>? = null, buildConfig: BuildConfig? = null) {
  if (buildConfig != null) {
    Platform.get().isDevelopmentMode = buildConfig.DEBUG
  } else if (buildConfigClass != null) {
    Platform.get().isDevelopmentMode = BuildConfig.fromClass(buildConfigClass).DEBUG
  } else {
    Platform.get().log("")
  }
}

/**
 * Consider using https://developer.android.com/training/articles/security-config#TrustingDebugCa
 * instead?
 */
fun OkHttpClient.Builder.enableDevAllowlist(vararg hosts: String) = apply {
  checkIsAndroid()

  val developmentMode = Platform.get().isDevelopmentMode
  check(developmentMode != null) {
    "setDevMode(BuildConfig.class) before use"
  }
  check(developmentMode) {
    "Not allowed for production builds"
  }

  val tm = Platform.get().platformTrustManager()
  val trustManager = AllowlistedTrustManager(tm, *hosts)
  val sf = Platform.get().newSSLContext().apply {
    init(null, arrayOf(trustManager), null)
  }.socketFactory
  sslSocketFactory(sf, trustManager)
}

fun checkIsAndroid() {
  check(Platform.get().isAndroid) {
    "Only for use in Android environments"
  }
}

fun OkHttpClient.Builder.enableCache(applicationContext: Context, cacheSize: Long = 10 * MiB) {
    cache(Cache(applicationContext.cacheDir.resolve("okhttp-cache"), cacheSize))
}
