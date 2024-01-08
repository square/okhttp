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
package okhttp3

import com.oracle.svm.core.annotate.AutomaticFeature
import java.io.File
import java.lang.IllegalStateException
import org.graalvm.nativeimage.hosted.Feature
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization
import org.graalvm.nativeimage.hosted.RuntimeReflection

@AutomaticFeature
class TestRegistration : Feature {
  override fun beforeAnalysis(access: Feature.BeforeAnalysisAccess) {
    // Presumably needed for parsing the testlist.txt file.
    RuntimeClassInitialization.initializeAtBuildTime(access.findClassByName("kotlin.text.Charsets"))

    registerKnownTests(access)

    registerJupiterClasses(access)

    registerParamProvider(access, "okhttp3.SampleTestProvider")
    registerParamProvider(access, "okhttp3.internal.http.CancelModelParamProvider")
    registerParamProvider(access, "okhttp3.internal.cache.FileSystemParamProvider")
    registerParamProvider(access, "okhttp3.internal.http2.HttpOverHttp2Test\$ProtocolParamProvider")
    registerParamProvider(access, "okhttp3.internal.cache.FileSystemParamProvider")
    registerParamProvider(access, "okhttp3.WebPlatformUrlTest\$TestDataParamProvider")
  }

  private fun registerParamProvider(
    access: Feature.BeforeAnalysisAccess,
    provider: String,
  ) {
    val providerClass = access.findClassByName(provider)
    if (providerClass != null) {
      registerTest(access, providerClass)
    } else {
      println("Missing $provider")
    }
  }

  private fun registerJupiterClasses(access: Feature.BeforeAnalysisAccess) {
    registerStandardClass(access, "org.junit.jupiter.params.ParameterizedTestExtension")
    registerStandardClass(access, "org.junit.platform.console.tasks.TreePrintingListener")
  }

  private fun registerStandardClass(
    access: Feature.BeforeAnalysisAccess,
    name: String,
  ) {
    val clazz: Class<*> = access.findClassByName(name) ?: throw IllegalStateException("Missing class $name")
    RuntimeReflection.register(clazz)
    clazz.declaredConstructors.forEach {
      RuntimeReflection.register(it)
    }
  }

  private fun registerKnownTests(access: Feature.BeforeAnalysisAccess) {
    val knownTestFile = File("src/main/resources/testlist.txt").absoluteFile
    knownTestFile.readLines().forEach {
      try {
        val testClass = access.findClassByName(it)

        if (testClass != null) {
          access.registerAsUsed(testClass)
          registerTest(access, testClass)
        }
      } catch (e: Exception) {
        // If you throw an exception here then native image building fails half way through
        // silently without rewriting the binary. So we report noisily, but keep going and prefer
        // running most tests still.
        e.printStackTrace()
      }
    }
  }

  private fun registerTest(
    access: Feature.BeforeAnalysisAccess,
    java: Class<*>,
  ) {
    access.registerAsUsed(java)
    RuntimeReflection.register(java)
    java.constructors.forEach {
      RuntimeReflection.register(it)
    }
    java.declaredMethods.forEach {
      RuntimeReflection.register(it)
    }
    java.declaredFields.forEach {
      RuntimeReflection.register(it)
    }
    java.methods.forEach {
      RuntimeReflection.register(it)
    }
  }
}
