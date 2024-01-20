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
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByName

fun Project.applyOsgi(vararg bndProperties: String) {
  plugins.withId("org.jetbrains.kotlin.jvm") {
    applyOsgi("jar", "osgiApi", bndProperties)
  }
}

private fun Project.applyOsgi(
  jarTaskName: String,
  osgiApiConfigurationName: String,
  bndProperties: Array<out String>,
) {
  val osgi = project.sourceSets.create("osgi")
  val osgiApi = project.configurations.getByName(osgiApiConfigurationName)
  val kotlinOsgi =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
      .findLibrary("kotlin.stdlib.osgi").get().get()

  project.dependencies {
    osgiApi(kotlinOsgi)
  }

  val jarTask = tasks.getByName<Jar>(jarTaskName)
  val bundleExtension =
    jarTask.extensions.findByType() ?: jarTask.extensions.create(
      BundleTaskExtension.NAME,
      BundleTaskExtension::class.java,
      jarTask,
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

val Project.sourceSets: SourceSetContainer
  get() = (this as ExtensionAware).extensions["sourceSets"] as SourceSetContainer
