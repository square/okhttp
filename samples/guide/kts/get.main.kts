#!/usr/bin/env -S kotlinc-jvm -nowarn -script

@file:Repository("https://jcenter.bintray.com")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.9.0")
@file:CompilerOptions("-jvm-target", "1.8")

import okhttp3.OkHttpClient
import okhttp3.Request

val client = OkHttpClient()

val request = Request.Builder()
   .url("https://raw.github.com/square/okhttp/master/README.md")
   .build()

val body = client.newCall(request).execute().use { response -> response.body!!.string() }

println(body)