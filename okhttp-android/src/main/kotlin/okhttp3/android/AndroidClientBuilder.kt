/*
 * Copyright (c) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package okhttp3.android

import android.content.Context
import android.net.http.HttpEngine
import androidx.annotation.RequiresApi
import com.google.net.cronet.okhttptransportU.CronetInterceptor
import okhttp3.OkHttpClient

@RequiresApi(10000)
fun OkHttpClient.Companion.newAndroidBuilder(context: Context, engineConfig: HttpEngine.Builder.() -> Unit = {}): OkHttpClient.Builder {
  val engine: HttpEngine = HttpEngine.Builder(context)
    .apply(engineConfig)
    .build()

  return OkHttpClient.Builder()
    .addInterceptor(CronetInterceptor.Builder(engine).build())
}
