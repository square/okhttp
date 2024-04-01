/*
 * Copyright (C) 2022 Block, Inc.
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
 */
package okhttp3.android

import android.util.Log
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.LoggingEventListener

/**
 * An OkHttp [LoggingEventListener], with android Log as the target.
 */
fun LoggingEventListener.Companion.androidLogging(
  priority: Int = Log.INFO,
  tag: String = "OkHttp",
) = LoggingEventListener.Factory { Log.println(priority, tag, it) }

/**
 * An OkHttp [HttpLoggingInterceptor], with android Log as the target.
 */
fun HttpLoggingInterceptor.Companion.androidLogging(
  priority: Int = Log.INFO,
  tag: String = "OkHttp",
) = HttpLoggingInterceptor { Log.println(priority, tag, it) }
