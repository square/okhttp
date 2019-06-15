Releasing
=========

1. Update `CHANGELOG.md`.

2. Set versions:

    ```
    export RELEASE_VERSION=X.Y.Z
    export NEXT_VERSION=X.Y.Z-SNAPSHOT
    ```

3. Update, build, and upload:

    ```
    sed -i "" \
      "s/VERSION_NAME=.*/VERSION_NAME=$RELEASE_VERSION/g" \
      gradle.properties
    sed -i "" \
      "s/\"com.squareup.okhttp3:\([^\:]*\):[^\"]*\"/\"com.squareup.okhttp3:\1:$RELEASE_VERSION\"/g" \
      `find . -name "README.md"`
    ./gradlew clean uploadArchives
    ```

4. Visit [Sonatype Nexus](https://oss.sonatype.org/) to promote the artifact. Or drop it if there is a problem!

5. Tag the release, prepare for the next one, and push to GitHub.

    ```
    git commit -am "Prepare for release $RELEASE_VERSION."
    git tag -a parent-$RELEASE_VERSION -m "Version $RELEASE_VERSION"
    sed -i "" \
      "s/VERSION_NAME=.*/VERSION_NAME=$NEXT_VERSION/g" \
      gradle.properties
    git commit -am "Prepare next development version."
    git push && git push --tags
    ```


Prerequisites
-------------

In `~/.gradle/gradle.properties`, set the following:

 * `SONATYPE_NEXUS_USERNAME` - Sonatype username for releasing to `com.squareup`.
 * `SONATYPE_NEXUS_PASSWORD` - Sonatype password for releasing to `com.squareup`.

