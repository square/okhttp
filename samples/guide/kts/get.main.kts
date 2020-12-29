#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.9.0")
@file:CompilerOptions("-jvm-target", "1.8")

import okhttp3.OkHttpClient
import okhttp3.Request

val client = OkHttpClient()

val request = Request.Builder()
   .url("https://raw.github.com/square/okhttp/master/README.md")
   .build()

val body = client.newCall(request).execute().use {
  it.body!!.string()
}

println(body)