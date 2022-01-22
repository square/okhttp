/*
 * Copyright (C) 2022 Square, Inc.
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

package okhttp3.internal.http

actual object HttpMethod {
  actual fun invalidatesCache(method: String): Boolean = commonInvalidatesCache(method)

  // Despite being 'internal', this method is called by popular 3rd party SDKs.
  actual fun requiresRequestBody(method: String): Boolean = commonRequiresRequestBody(method)

  // Despite being 'internal', this method is called by popular 3rd party SDKs.
  actual fun permitsRequestBody(method: String): Boolean = commonPermitsRequestBody(method)

  actual fun redirectsWithBody(method: String): Boolean = commonRedirectsWithBody(method)

  actual fun redirectsToGet(method: String): Boolean = commonRedirectsToGet(method)
}
