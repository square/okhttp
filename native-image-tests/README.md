Native Image Tests
==================

This executes OkHttp's test suite inside a Graalvm image.

Execute
-------

The native image runs JUnit 5 tests in the project.

```
./gradlew -PgraalBuild=true --info native-image-tests:nativeTest
```

