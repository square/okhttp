/*
 * Copyright (C) 2021 Square, Inc.
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
@file:Suppress("ktlint:standard:filename")

package okhttp3.internal

import okhttp3.Challenge

fun Challenge.commonEquals(other: Any?): Boolean {
  return other is Challenge &&
    other.scheme == scheme &&
    other.authParams == authParams
}

fun Challenge.commonHashCode(): Int {
  var result = 29
  result = 31 * result + scheme.hashCode()
  result = 31 * result + authParams.hashCode()
  return result
}

fun Challenge.commonToString(): String = "$scheme authParams=$authParams"
