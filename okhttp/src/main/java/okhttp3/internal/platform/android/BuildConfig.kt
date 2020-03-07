package okhttp3.internal.platform.android

@Suppress("PropertyName")
class BuildConfig internal constructor(val DEBUG: Boolean) {
  companion object {
    fun fromClass(buildConfig: Class<*>): BuildConfig {
      check(buildConfig.simpleName == "BuildConfig") {
        "Please provide BuildConfig class, received: " + buildConfig.name
      }

      val debug = buildConfig.getField("DEBUG").getBoolean(null)

      return BuildConfig(debug)
    }
  }
}
