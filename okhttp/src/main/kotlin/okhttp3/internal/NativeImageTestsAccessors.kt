/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal

import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Response
import okhttp3.internal.connection.Exchange
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealConnection
import okio.ExperimentalFileSystem
import okio.FileSystem
import okio.Path

@OptIn(ExperimentalFileSystem::class)
fun buildCache(file: Path, maxSize: Long, fileSystem: FileSystem): Cache {
  return Cache(file, maxSize, fileSystem)
}

var RealConnection.idleAtNsAccessor: Long
  get() = idleAtNs
  set(value) {
    idleAtNs = value
  }

val Response.exchangeAccessor: Exchange?
  get() = this.exchange

val Exchange.connectionAccessor: RealConnection
  get() = this.connection

fun Dispatcher.finishedAccessor(call: RealCall.AsyncCall) = this.finished(call)
