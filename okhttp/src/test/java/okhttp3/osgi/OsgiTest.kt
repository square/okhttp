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
package okhttp3.osgi

import aQute.bnd.build.Project
import aQute.bnd.build.Workspace
import aQute.bnd.build.model.BndEditModel
import aQute.bnd.deployer.repository.LocalIndexedRepo
import aQute.bnd.osgi.Constants
import aQute.bnd.service.RepositoryPlugin
import biz.aQute.resolve.Bndrun
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Slow")
class OsgiTest {
  private lateinit var testResourceDir: Path
  private lateinit var workspaceDir: Path

  @BeforeEach
  fun setUp() {
    testResourceDir = "./build/resources/test/okhttp3/osgi".toPath()
    workspaceDir = testResourceDir / "workspace"

    // Ensure we start from scratch.
    fileSystem.deleteRecursively(workspaceDir)
    fileSystem.createDirectories(workspaceDir)
  }

  /**
   * Resolve the OSGi metadata of the all okhttp3 modules. If required modules do not have OSGi
   * metadata this will fail with an exception.
   */
  @Test
  fun testMainModuleWithSiblings() {
    createWorkspace().use { workspace ->
      createBndRun(workspace).use { bndRun ->
        bndRun.resolve(
          false,
          false,
        )
      }
    }
  }

  private fun createWorkspace(): Workspace {
    val bndDir = workspaceDir / "cnf"
    val repoDir = bndDir / "repo"
    fileSystem.createDirectories(repoDir)
    return Workspace(workspaceDir.toFile(), bndDir.name)
      .apply {
        setProperty(
          "${Constants.PLUGIN}.$REPO_NAME",
          LocalIndexedRepo::class.java.getName() +
            "; ${LocalIndexedRepo.PROP_NAME} = '$REPO_NAME'" +
            "; ${LocalIndexedRepo.PROP_LOCAL_DIR} = '$repoDir'",
        )
        refresh()
        prepareWorkspace()
      }
  }

  private fun Workspace.prepareWorkspace() {
    val repositoryPlugin = getRepository(REPO_NAME)

    // Deploy the bundles in the deployments test directory.
    repositoryPlugin.deployDirectory(testResourceDir / "deployments")
    repositoryPlugin.deployClassPath()
  }

  private fun createBndRun(workspace: Workspace): Bndrun {
    // Creating the run require string. It will always use the latest version of each bundle
    // available in the repository.
    val runRequireString =
      REQUIRED_BUNDLES.joinToString(separator = ",") {
        "osgi.identity;filter:='(osgi.identity=$it)'"
      }

    val bndEditModel =
      BndEditModel(workspace).apply {
        // Temporary project to satisfy bnd API.
        project = Project(workspace, workspaceDir.toFile())
      }

    return Bndrun(bndEditModel).apply {
      setRunfw(RESOLVE_OSGI_FRAMEWORK)
      runee = RESOLVE_JAVA_VERSION
      setRunRequires(runRequireString)
    }
  }

  private fun RepositoryPlugin.deployDirectory(directory: Path) {
    for (path in fileSystem.list(directory)) {
      deployFile(path)
    }
  }

  private fun RepositoryPlugin.deployClassPath() {
    val classpath = System.getProperty("java.class.path")
    val entries =
      classpath.split(File.pathSeparator.toRegex())
        .dropLastWhile { it.isEmpty() }
        .toTypedArray()
    for (classPathEntry in entries) {
      deployFile(classPathEntry.toPath())
    }
  }

  private fun RepositoryPlugin.deployFile(file: Path) {
    if (fileSystem.metadataOrNull(file)?.isRegularFile != true) return
    try {
      fileSystem.read(file) {
        put(inputStream(), RepositoryPlugin.PutOptions())
        println("Deployed ${file.name}")
      }
    } catch (e: IllegalArgumentException) {
      if ("Jar does not have a symbolic name" in e.message!!) {
        println("Skipped non-OSGi dependency: ${file.name}")
        return
      }
      throw e
    }
  }

  companion object {
    val fileSystem = FileSystem.SYSTEM

    /** Each is the Bundle-SymbolicName of an OkHttp module's OSGi configuration.  */
    private val REQUIRED_BUNDLES: List<String> =
      mutableListOf(
        "com.squareup.okhttp3",
        "com.squareup.okhttp3.brotli",
        "com.squareup.okhttp3.dnsoverhttps",
        "com.squareup.okhttp3.logging",
        "com.squareup.okhttp3.sse",
        "com.squareup.okhttp3.tls",
        "com.squareup.okhttp3.urlconnection",
      )

    /** Equinox must also be on the testing classpath.  */
    private const val RESOLVE_OSGI_FRAMEWORK = "org.eclipse.osgi"
    private const val RESOLVE_JAVA_VERSION = "JavaSE-1.8"
    private const val REPO_NAME = "OsgiTest"
  }
}
