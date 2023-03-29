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
import android.os.Build
import androidx.annotation.RequiresApi
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun OkHttpClient.newAndroidBuilder(context: Context): OkHttpClient.Builder {
  val engine: android.net.http.HttpEngine = HttpEngine.Builder(context)
    .build()

  return OkHttpClient.Builder()
}
