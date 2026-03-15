/*
 * Copyright (C) 2025 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import me.champeau.mrjar.MultiReleaseExtension
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

fun Project.applyJavaModules(
  moduleName: String,
  defaultVersion: Int = 8,
  javaModuleVersion: Int = 9,
  enableValidation: Boolean = true,
) {
  plugins.apply("me.champeau.mrjar")

  configure<MultiReleaseExtension> {
    targetVersions(defaultVersion, javaModuleVersion)
  }

  tasks.named<JavaCompile>("compileJava9Java").configure {
    val compileKotlinTask = tasks.getByName("compileKotlin") as KotlinJvmCompile
    dependsOn(compileKotlinTask)

    if (enableValidation) {
      compileKotlinTask.source(file("src/main/java9"))
    }

    // Ignore warnings about using 'requires transitive' on automatic modules.
    // not needed when compiling with recent JDKs, e.g. 17
    options.compilerArgs.add("-Xlint:-requires-transitive-automatic")

    // Patch the compileKotlinJvm output classes into the compilation so exporting packages works correctly.
    options.compilerArgs.addAll(
      listOf(
        "--patch-module",
        "$moduleName=${compileKotlinTask.destinationDirectory.get().asFile}",
      ),
    )

    classpath = compileKotlinTask.libraries
    modularity.inferModulePath.set(true)

    val javaToolchains = project.extensions.getByType<JavaToolchainService>()
    val javaPluginExtension = project.extensions.getByType<JavaPluginExtension>()
    javaCompiler.set(javaToolchains.compilerFor(javaPluginExtension.toolchain))
  }
}
