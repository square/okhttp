package okhttp3.osgi;

import aQute.bnd.build.Workspace;
import aQute.bnd.service.RepositoryPlugin;
import aQute.lib.io.IO;
import biz.aQute.resolve.Bndrun;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Tries to resolve the OSGi metadata specified in the
 */
public class OkHttpOsgiTest {
    @Test
    public void testMainModuleWithSiblings() throws Exception {
        File workspaceDir = getWorkspaceDirectory().toFile();
        Path resourceDir = getResourceDirectory();
        Workspace workspace = new Workspace(workspaceDir);

        RepositoryPlugin localRepo = workspace.getRepository("Local");
        // deploy the bundles in the deployments test directory
        deployDirectory(localRepo, resourceDir.resolve("deployments"));
        deployClassPath(localRepo);

        try (Bndrun bndRun = new Bndrun(workspace, resourceDir.resolve("resolveTest.bndrun").toFile())) {
            // this will fail when something is wrong
            bndRun.resolve(false, true);
        }
    }

    private Path getResourceDirectory() throws URISyntaxException {
        return getWorkspaceDirectory().getParent();
    }

    private Path getWorkspaceDirectory() throws URISyntaxException {
        URL bndWorkspaceURL = OkHttpOsgiTest.class.getResource("ws1/cnf/build.bnd");
        Path bndWorkspaceFile = Paths.get(bndWorkspaceURL.toURI());
        return bndWorkspaceFile.getParent().getParent();
    }

    private void deployDirectory(RepositoryPlugin repository, Path directory) throws IOException {
        if (!Files.exists(directory))
            return;

        try (Stream<Path> files = Files.list(directory)) {
            files.forEach(f -> {
                deployFile(repository, f);
            });
        }
    }

    private void deployClassPath(RepositoryPlugin repository) {
        String classpath = System.getProperty("java.class.path");
        String[] classpathEntries = classpath.split(File.pathSeparator);
        for (String classPathEntry : classpathEntries) {
            Path classPathFile = Paths.get(classPathEntry);
            deployFile(repository, classPathFile);
        }
    }

    private void deployFile(RepositoryPlugin repository, Path file) {
        if (Files.isRegularFile(file)) {
            String fileName = file.getFileName().toString();
            try {
                deploy(repository, file);
                successDeployment(fileName);
            } catch (Exception e) {
                failedDeployment(fileName, e);
            }
        }
    }

    private void deploy(RepositoryPlugin repository, Path bundleFile) throws Exception {
        try (InputStream stream = new BufferedInputStream(IO.stream(bundleFile))) {
            repository.put(stream, new RepositoryPlugin.PutOptions());
        }
    }

    private void successDeployment(String fileName) {
        System.out.println("Deployed " + fileName);
    }

    private void failedDeployment(String fileName, Exception error) {
        System.out.println("Failed to deploy " + fileName);
    }
}
