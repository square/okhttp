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

import aQute.bnd.gradle.BundleTaskConvention
import java.io.File
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withConvention

object Projects {
  /** Returns the artifact ID for the project, or null if it is not published. */
  @JvmStatic
  fun publishedArtifactId(projectName: String): String? {
    return when (projectName) {
      "okhttp-logging-interceptor" -> "logging-interceptor"
      "mockwebserver" -> "mockwebserver3"
      "mockwebserver-junit4" -> "mockwebserver3-junit4"
      "mockwebserver-junit5" -> "mockwebserver3-junit5"
      "mockwebserver-deprecated" -> "mockwebserver"
      "okcurl",
      "okhttp",
      "okhttp-bom",
      "okhttp-brotli",
      "okhttp-dnsoverhttps",
      "okhttp-sse",
      "okhttp-tls",
      "okhttp-urlconnection" -> projectName
      else -> null
    }
  }

  @JvmStatic
  fun applyOsgi(project: Project, vararg bndProperties: String) {
    project.run {
      apply(plugin = "biz.aQute.bnd.builder")
      sourceSets.create("osgi")
      tasks["jar"].withConvention(BundleTaskConvention::class) {
        setClasspath(sourceSets["osgi"].compileClasspath + project.sourceSets["main"].compileClasspath)
        bnd(*bndProperties)
      }
      dependencies.add("osgiApi", Dependencies.kotlinStdlibOsgi)
    }
  }

  /**
   * Returns a .jar file for the golden version of this project.
   * https://github.com/Visistema/Groovy1/blob/ba5eb9b2f19ca0cc8927359ce414c4e1974b7016/gradle/binarycompatibility.gradle#L48
   */
  @JvmStatic
  @JvmOverloads
  fun baselineJar(project: Project, version: String = "3.14.1"): File? {
    val group = project.group
    val artifactId = project.extra["artifactId"]
    return try {
      val jarFile = "$artifactId-${version}.jar"
      project.group = "virtual_group_for_japicmp"
      val dependency = project.dependencies.create("$group:$artifactId:$version@jar")
      project.configurations.detachedConfiguration(dependency).files.find { (it.name == jarFile) }
    } catch (e: Exception) {
      null
    } finally {
      project.group = group
    }
  }
}

val Project.sourceSets: SourceSetContainer
  get() = (this as ExtensionAware).extensions.getByName("sourceSets") as SourceSetContainer
