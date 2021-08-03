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
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.apply
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
}

val org.gradle.api.Project.sourceSets: SourceSetContainer
  get() = (this as ExtensionAware).extensions.getByName("sourceSets") as SourceSetContainer
