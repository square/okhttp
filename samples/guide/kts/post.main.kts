#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.9.0")
@file:CompilerOptions("-jvm-target", "1.8")

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val client = OkHttpClient()

val json: String =
  "{'winCondition':'HIGH_SCORE','name':'Bowling','round':4,'lastSaved':1367702411696,'dateStarted':1367702378785,'players':[{'name':'Jesse','history':[10,8,6,7,8],'color':-13388315,'total':39},{'name':'Jake','history':[6,10,5,10,10],'color':-48060,'total':41}]}"
val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

val request: Request = Request.Builder()
  .url("http://www.roundsapp.com/post")
  .post(body)
  .build()

client.newCall(request).execute().use {
  println(it.body?.string())
}