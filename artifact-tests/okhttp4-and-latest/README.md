OkHttp4 and Latest
==================

We're attempting to validate that users can upgrade from OkHttp 4.x to OkHttp 5.x without doing
anything beyond changing the version number.

The challenge is that OkHttp 4.x was packaged as a basic Maven library, and OkHttp 5.x is a
multiplatform library with separate `-jvm` and `-android` artifacts.


Publish the current version to `build/localMaven`

```
./gradlew publishAllPublicationsToLocalMavenRepository
```

Confirm a downstream project can consume it:

```
cd artifact-tests/okhttp4-and-latest
../../gradlew check connectedCheck
```
