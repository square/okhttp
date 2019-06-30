/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.http.RealInterceptorChain
import java.io.IOException

/** Opens a connection to the target server and proceeds to the next interceptor. */
object ConnectInterceptor : Interceptor {

  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    val request = realChain.request()
    val transmitter = realChain.transmitter()

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    val doExtensiveHealthChecks = request.method != "GET"
    val exchange = transmitter.newExchange(chain, doExtensiveHealthChecks)

    return realChain.proceed(request, transmitter, exchange)
  }
}
