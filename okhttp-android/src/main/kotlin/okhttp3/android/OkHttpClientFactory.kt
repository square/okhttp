package okhttp3.android

import okhttp3.OkHttpClient

interface OkHttpClientFactory {
  fun newOkHttpClient(): OkHttpClient
}
