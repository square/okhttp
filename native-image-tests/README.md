Native Image Tests
==================

This executes OkHttp's test suite inside a Graalvm image.

Seeding Graalvm Config
----------------------

This isn't yet integrated into the Graalvm Gradle plugin, so you'll need to install your own
Graalvm and edit `build.gradle` to set its location.

```
def graalHome = "/Library/Java/JavaVirtualMachines/graalvm-ce-java11-20.2.0/Contents/Home"
``` 

The task runs tests in the JVM, collecting information about which classes require reflection 
metadata. This includes the test classes and some parts of the JUnit framework.

```
./gradlew --info native-image-tests:seedGraalvmConfig
```

This will create a directory of metadata:

    okhttp
    '- native-image-tests
      '- build
        '- graalvm
          '- resources
            '- META-INF
              '- native-image
                |- jni-config.json
                |- proxy-config.json
                |- reflect-config.json
                '- resource-config.json

Build the Native Image
----------------------

Compile the classes and metadata into a Graalvm native image.

```
./gradlew --info native-image-tests:nativeImage
```


Execute
-------

The native image runs JUnit 5's `ConsoleLauncher`.

```
./native-image-tests/build/graal/ConsoleLauncher --select-class okhttp3.SampleTest
```

