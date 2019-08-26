Releasing
=========

### Prerequisite: Sonatype (Maven Central) Account

Create an account on the [Sonatype issues site][sonatype_issues]. Ask an existing publisher to open
an issue requesting publishing permissions for `com.squareup` projects.

### Prerequisite: GPG Keys

Generate a GPG key (RSA, 4096 bit, 3650 day) expiry, or use an existing one. You should leave the
password empty for this key.

```
$ gpg --full-generate-key
```

Upload the GPG keys to public servers:

```
$ gpg --list-keys --keyid-format LONG
/Users/johnbarber/.gnupg/pubring.kbx
------------------------------
pub   rsa4096/XXXXXXXXXXXXXXXX 2019-07-16 [SC] [expires: 2029-07-13]
      YYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYYY
uid           [ultimate] John Barber <jbarber@squareup.com>
sub   rsa4096/ZZZZZZZZZZZZZZZZ 2019-07-16 [E] [expires: 2029-07-13]

$ gpg --send-keys --keyserver keyserver.ubuntu.com XXXXXXXXXXXXXXXX
```

### Prerequisite: Gradle Properties

Define publishing properties in `~/.gradle/gradle.properties`:

```
signing.keyId=1A2345F8
signing.password=
signing.secretKeyRingFile=/Users/jbarber/.gnupg/secring.gpg
```

`signing.keyId` is the GPG key's ID. Get it with this:

   ```
   $ gpg --list-keys --keyid-format SHORT
   ```

`signing.password` is the password for this key. This might be empty!

`signing.secretKeyRingFile` is the absolute path for `secring.gpg`. You may need to export this
file manually with the following command where `XXXXXXXX` is the `keyId` above:

   ```
   $ gpg --keyring secring.gpg --export-secret-key XXXXXXXX > ~/.gnupg/secring.gpg
   ```


Cutting a Release
-----------------

1. Update `CHANGELOG.md`.

2. Set versions:

    ```
    export RELEASE_VERSION=X.Y.Z
    export NEXT_VERSION=X.Y.Z-SNAPSHOT
    ```

3. Set environment variables with your [Sonatype credentials][sonatype_issues].

    ```
    export SONATYPE_NEXUS_USERNAME=johnbarber
    export SONATYPE_NEXUS_PASSWORD=`pbpaste`
    ```

4. Update, build, and upload:

    ```
    sed -i "" \
      "s/VERSION_NAME=.*/VERSION_NAME=$RELEASE_VERSION/g" \
      gradle.properties
    sed -i "" \
      "s/\"com.squareup.okhttp3:\([^\:]*\):[^\"]*\"/\"com.squareup.okhttp3:\1:$RELEASE_VERSION\"/g" \
      `find . -name "README.md"`
    ./gradlew clean uploadArchives
    ```

5. Visit [Sonatype Nexus][sonatype_nexus] to promote (close then release) the artifact. Or drop it
   if there is a problem!

6. Tag the release, prepare for the next one, and push to GitHub.

    ```
    git commit -am "Prepare for release $RELEASE_VERSION."
    git tag -a parent-$RELEASE_VERSION -m "Version $RELEASE_VERSION"
    sed -i "" \
      "s/VERSION_NAME=.*/VERSION_NAME=$NEXT_VERSION/g" \
      gradle.properties
    git commit -am "Prepare next development version."
    git push && git push --tags
    ```

 [sonatype_issues]: https://issues.sonatype.org/
 [sonatype_nexus]: https://oss.sonatype.org/