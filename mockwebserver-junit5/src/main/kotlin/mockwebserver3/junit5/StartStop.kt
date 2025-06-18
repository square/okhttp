/*
 * Copyright (C) 2025 Square, Inc.
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
package mockwebserver3.junit5

import mockwebserver3.junit5.internal.StartStopExtension
import okhttp3.ExperimentalOkHttpApi
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Runs MockWebServer for the duration of a test method or test class.
 *
 * In Java JUnit 5 tests (ie. tests annotated `@org.junit.jupiter.api.Test`), use this by defining a
 * field with the `@StartStop` annotation:
 *
 * ```java
 * @StartStop public final MockWebServer server = new MockWebServer();
 * ```
 *
 * Or for Kotlin:
 *
 * ```kotlin
 * @StartStop val server = MockWebServer()
 * ```
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(StartStopExtension::class)
@ExperimentalOkHttpApi
annotation class StartStop
