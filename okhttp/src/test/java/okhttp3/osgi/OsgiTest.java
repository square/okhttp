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
package okhttp3.osgi;

import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.build.model.BndEditModel;
import aQute.bnd.deployer.repository.LocalIndexedRepo;
import aQute.bnd.osgi.Constants;
import aQute.bnd.service.RepositoryPlugin;
import biz.aQute.resolve.Bndrun;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import okio.BufferedSource;
import okio.Okio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class OsgiTest {
  /** Each is the Bundle-SymbolicName of an OkHttp module's OSGi configuration. */
  private static final List<String> REQUIRED_BUNDLES = Arrays.asList(
      "com.squareup.okhttp3",
      "com.squareup.okhttp3.brotli",
      "com.squareup.okhttp3.dnsoverhttps",
      "com.squareup.okhttp3.logging",
      "com.squareup.okhttp3.sse",
      "com.squareup.okhttp3.tls",
      "com.squareup.okhttp3.urlconnection"
  );

  /** Equinox must also be on the testing classpath. */
  private static final String RESOLVE_OSGI_FRAMEWORK = "org.eclipse.osgi";
  private static final String RESOLVE_JAVA_VERSION = "JavaSE-1.8";
  private static final String REPO_NAME = "OsgiTest";

  private File testResourceDir;
  private File workspaceDir;

  @BeforeEach
  public void setUp() throws Exception {
    testResourceDir = new File("./build/resources/test/okhttp3/osgi");
    workspaceDir = new File(testResourceDir, "workspace");

    // Ensure we start from scratch.
    deleteDirectory(workspaceDir);
    workspaceDir.mkdirs();
  }

  /**
   * Resolve the OSGi metadata of the all okhttp3 modules. If required modules do not have OSGi
   * metadata this will fail with an exception.
   */
  @Test
  public void testMainModuleWithSiblings() throws Exception {
    try (Workspace workspace = createWorkspace();
         Bndrun bndRun = createBndRun(workspace)) {
      bndRun.resolve(false, false);
    }
  }

  private Workspace createWorkspace() throws Exception {
    File bndDir = new File(workspaceDir, "cnf");
    File repoDir = new File(bndDir, "repo");
    repoDir.mkdirs();

    Workspace workspace = new Workspace(workspaceDir, bndDir.getName());
    workspace.setProperty(Constants.PLUGIN + "." + REPO_NAME, ""
        + LocalIndexedRepo.class.getName()
        + "; " + LocalIndexedRepo.PROP_NAME + " = '" + REPO_NAME + "'"
        + "; " + LocalIndexedRepo.PROP_LOCAL_DIR + " = '" + repoDir + "'");
    workspace.refresh();
    prepareWorkspace(workspace);
    return workspace;
  }

  private void prepareWorkspace(Workspace workspace) throws Exception {
    RepositoryPlugin repositoryPlugin = workspace.getRepository(REPO_NAME);

    // Deploy the bundles in the deployments test directory.
    deployDirectory(repositoryPlugin, new File(testResourceDir, "deployments"));
    deployClassPath(repositoryPlugin);
  }

  private Bndrun createBndRun(Workspace workspace) throws Exception {
    // Creating the run require string. It will always use the latest version of each bundle
    // available in the repository.
    String runRequireString = REQUIRED_BUNDLES.stream()
        .map(s -> "osgi.identity;filter:='(osgi.identity=" + s + ")'")
        .collect(Collectors.joining(","));

    BndEditModel bndEditModel = new BndEditModel(workspace);
    // Temporary project to satisfy bnd API.
    bndEditModel.setProject(new Project(workspace, workspaceDir));

    Bndrun result = new Bndrun(bndEditModel);
    result.setRunfw(RESOLVE_OSGI_FRAMEWORK);
    result.setRunee(RESOLVE_JAVA_VERSION);
    result.setRunRequires(runRequireString);
    return result;
  }

  private void deployDirectory(RepositoryPlugin repository, File directory) throws Exception {
    File[] files = directory.listFiles();
    if (files == null) return;

    for (File file : files) {
      deployFile(repository, file);
    }
  }

  private void deployClassPath(RepositoryPlugin repositoryPlugin) throws Exception {
    String classpath = System.getProperty("java.class.path");
    for (String classPathEntry : classpath.split(File.pathSeparator)) {
      deployFile(repositoryPlugin, new File(classPathEntry));
    }
  }

  private void deployFile(RepositoryPlugin repositoryPlugin, File file) throws Exception {
    if (!file.exists() || file.isDirectory()) return;

    try (BufferedSource source = Okio.buffer(Okio.source(file))) {
      repositoryPlugin.put(source.inputStream(), new RepositoryPlugin.PutOptions());
      System.out.println("Deployed " + file.getName());
    } catch (IllegalArgumentException e) {
      if (e.getMessage().contains("Jar does not have a symbolic name")) {
        System.out.println("Skipped non-OSGi dependency: " + file.getName());
        return;
      }
      throw e;
    }
  }

  private static void deleteDirectory(File dir) throws IOException {
    if (!dir.exists()) return;

    Files.walk(dir.toPath())
        .filter(Files::isRegularFile)
        .map(Path::toFile)
        .forEach(File::delete);
  }
}
