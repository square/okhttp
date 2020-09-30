package okhttp3.osgi;

import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.build.model.BndEditModel;
import aQute.bnd.deployer.repository.LocalIndexedRepo;
import aQute.bnd.osgi.Constants;
import aQute.bnd.service.RepositoryPlugin;
import aQute.bnd.stream.MapStream;
import aQute.lib.io.IO;
import biz.aQute.resolve.Bndrun;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tries to resolve the OSGi metadata of the okhttp3 main module and all siblings.
 * If some required modules do not have OSGi metadata the test will fail.
 */
public class OkHttpOsgiTest {
    private static final List<String> RUN_REQ = Arrays.asList(
            "com.squareup.okhttp3",
            "com.squareup.okhttp3.brotli",
            "com.squareup.okhttp3.dnsoverhttps",
            "com.squareup.okhttp3.logging",
            "com.squareup.okhttp3.sse",
            "com.squareup.okhttp3.tls",
            "com.squareup.okhttp3.urlconnection"
    );
    // equinox
    // the framework defined here must also be on the testing classpath
    private static final String RESOLVE_OSGI_FRAMEWORK = "org.eclipse.osgi";
    private static final String RESOLVE_JAVA_VERSION = "JavaSE-1.8";
    private static final String LOCAL_REPO_NAME = "Local";

    private static Path TEST_RESOURCE_DIR;
    private static Path WORKING_DIR;

    @BeforeClass
    public static void setup() throws IOException {
        TEST_RESOURCE_DIR = Paths.get("./build/resources/test/okhttp3/osgi");
        TEST_RESOURCE_DIR = TEST_RESOURCE_DIR.toRealPath(LinkOption.NOFOLLOW_LINKS);
        WORKING_DIR = TEST_RESOURCE_DIR.resolve("tmp");
        // ensure we start from scratch
        deleteDirectory(WORKING_DIR);
        Files.createDirectories(WORKING_DIR);
    }

    @Test
    public void testMainModuleWithSiblings() throws Exception {
        try (Workspace workspace = createWorkspace(); Bndrun bndRun = createBndRun(workspace)) {
            // this will fail when something is wrong
            bndRun.resolve(false, false);
        }
    }

    private Workspace createWorkspace() throws Exception {
        Path workingDir = WORKING_DIR;
        Path cnfDir = workingDir.resolve("cnf");
        Path localRepoDir = cnfDir.resolve("local");
        Files.createDirectories(localRepoDir);

        Workspace workspace = new Workspace(workingDir.toFile(), cnfDir.getFileName().toString());
        // add local repository plugin to which we deploy the bundles to
        addPlugin(workspace, LOCAL_REPO_NAME, LocalIndexedRepo.class, LocalIndexedRepo.PROP_NAME, LOCAL_REPO_NAME, LocalIndexedRepo.PROP_LOCAL_DIR, localRepoDir.toString());
        workspace.refresh();
        prepareWorkspace(workspace);
        return workspace;
    }

    private void prepareWorkspace(Workspace workspace) throws Exception {
        Path testResDir = TEST_RESOURCE_DIR;
        Path deploymentsDir = testResDir.resolve("deployments");

        RepositoryPlugin localRepo = workspace.getRepository(LOCAL_REPO_NAME);
        // deploy the bundles in the deployments test directory
        deployDirectory(localRepo, deploymentsDir);
        deployClassPath(localRepo);
    }

    private void addPlugin(Workspace workspace, String alias, Class<?> pluginType, String key1, String value1, String key2, String value2) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(key1, value1);
        props.put(key2, value2);
        addPlugin(workspace, alias, pluginType, props);
    }

    private void addPlugin(Workspace workspace, String alias, Class<?> pluginType, Map<String, String> properties) {
        try (Formatter setup = new Formatter()) {
            setup.format("%s", pluginType.getName());
            MapStream.of(properties)
                    .forEachOrdered((k, v) -> setup.format("; %s = '%s'", k, v));
            workspace.setProperty(Constants.PLUGIN + "." + alias, setup.toString());
        }
    }

    private Bndrun createBndRun(Workspace workspace) throws Exception {
        // creating the run require sting
        // it will always use the latest version of each bundle available in the repository
        String runRequireString = RUN_REQ.stream().map(
                s -> "osgi.identity;filter:='(osgi.identity=" + s + ")'"
        ).collect(
                Collectors.joining(",")
        );

        BndEditModel runEditModel = new BndEditModel(workspace);
        // tmp project to satisfy bnd API
        runEditModel.setProject(new Project(workspace, WORKING_DIR.toFile()));
        Bndrun bndrun = new Bndrun(runEditModel);
        bndrun.setRunfw(RESOLVE_OSGI_FRAMEWORK);
        bndrun.setRunee(RESOLVE_JAVA_VERSION);
        bndrun.setRunRequires(runRequireString);
        return bndrun;
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

    // called when e.g. module is no OSGi bundle
    private void failedDeployment(String fileName, Exception error) {
        if (error instanceof IllegalArgumentException || !(error instanceof RuntimeException)) {
            System.out.println("Failed to deploy " + fileName);
        } else {
            throw (RuntimeException) error;
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        Files.walk(dir)
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
