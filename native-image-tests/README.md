Native Image Tests
==================

This executes OkHttp's test suite inside a Graalvm image.

Build the Native Image
----------------------

Compile the classes and metadata into a Graalvm native image.

```
./gradlew --info native-image-tests:nativeImage
```

Execute
-------

The native image runs JUnit 5 tests in the project.

```
./native-image-tests/build/graal/ConsoleLauncher
```

