/*
 * Copyright (C) 2020 Square, Inc.
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
package okhttp3.nativeimage

import assertk.assertThat
import assertk.assertions.isGreaterThan
import java.util.stream.Stream
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.support.ParameterDeclarations

/**
 * This enforces us having the params classes on the classpath to workaround
 * https://github.com/graalvm/native-build-tools/issues/745
 */
class WithArgumentSourceTest {
  @ParameterizedTest
  @ArgumentsSource(FakeArgumentsProvider::class)
  fun passingTest(value: Int) {
    assertThat(value).isGreaterThan(0)
  }
}

internal class FakeArgumentsProvider : ArgumentsProvider {
  override fun provideArguments(
    parameters: ParameterDeclarations?,
    context: ExtensionContext?,
  ): Stream<out Arguments> = listOf(Arguments.of(1), Arguments.of(2)).stream()
}
