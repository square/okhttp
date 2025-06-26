package artifacttests

import okhttp3.OkHttpClient

class DependsOnLatest {
  fun execute() {
    OkHttpClient()
  }
}
