/*
 * Copyright (C) 2021 Square, Inc.
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

import aQute.bnd.gradle.BundleTaskExtension
import java.io.File
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName

fun Project.applyOsgi(vararg bndProperties: String) {
  // Configure OSGi for the JVM platform on kotlin-multiplatform.
  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    applyOsgi("jvmJar", "jvmOsgiApi", bndProperties)
  }

  // Configure OSGi for kotlin-jvm.
  plugins.withId("org.jetbrains.kotlin.jvm") {
    applyOsgi("jar", "osgiApi", bndProperties)
  }
}

private fun Project.applyOsgi(
  jarTaskName: String,
  osgiApiConfigurationName: String,
  bndProperties: Array<out String>
) {
  val osgi = project.sourceSets.create("osgi")
  val osgiApi = project.configurations.getByName(osgiApiConfigurationName)
  project.dependencies {
    osgiApi(Dependencies.kotlinStdlibOsgi)
  }

  val jarTask = tasks.getByName<Jar>(jarTaskName)
  val bundleExtension = jarTask.extensions.findByType() ?: jarTask.extensions.create(
    BundleTaskExtension.NAME, BundleTaskExtension::class.java, jarTask
  )
  bundleExtension.run {
    setClasspath(osgi.compileClasspath + sourceSets["main"].compileClasspath)
    bnd(*bndProperties)
  }
  // Call the convention when the task has finished, to modify the jar to contain OSGi metadata.
  jarTask.doLast {
    bundleExtension.buildAction().execute(this)
  }
}

/**
 * Returns a .jar file for the golden version of this project.
 * https://github.com/Visistema/Groovy1/blob/ba5eb9b2f19ca0cc8927359ce414c4e1974b7016/gradle/binarycompatibility.gradle#L48
 */
fun Project.baselineJar(version: String = "3.14.1"): File? {
  val originalGroup = group
  return try {
    val jarFile = "$name-$version.jar"
    group = "virtual_group_for_japicmp"
    val dependency = dependencies.create("$originalGroup:$name:$version@jar")
    configurations.detachedConfiguration(dependency).files.find { (it.name == jarFile) }
  } catch (e: Exception) {
    null
  } finally {
    group = originalGroup
  }
}

val Project.sourceSets: SourceSetContainer
  get() = (this as ExtensionAware).extensions["sourceSets"] as SourceSetContainer
