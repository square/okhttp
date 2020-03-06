package okhttp3.internal.platform.android

@Suppress("PropertyName")
class BuildConfig(buildConfig: Class<*>) {
  init {
    check(buildConfig.simpleName == "BuildConfig") {
      "Please provide BuildConfig class, received: " + buildConfig.name
    }
  }

  val DEBUG = buildConfig.getField("DEBUG").getBoolean(null)
}
