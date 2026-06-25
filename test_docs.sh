#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material mkdocs-redirects

set -ex

# Test generating the javadoc jars
./gradlew publishToMavenLocal -DRELEASE_SIGNING_ENABLED=false -PokhttpDokka=true

# Generate the API docs
./gradlew dokkaGeneratePublicationHtml -PokhttpDokka=true

mv ./build/dokka/html docs/5.x

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelogs/changelog.md
cp CONTRIBUTING.md docs/contribute/contributing.md

# Create a redirect page for OkHttp 4.x API docs.
# The 4.x docs redirect to 5.x since the API surface is compatible.
mkdir -p docs/4.x/okhttp/okhttp3
cat > docs/4.x/okhttp/okhttp3/index.html << 'REDIRECT'
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>Redirecting&hellip;</title>
    <script>
        var path = window.location.pathname;
        var idx = path.indexOf('/4.x/');
        if (idx !== -1) {
            window.location.replace(path.substring(0, idx) + '/5.x' + path.substring(idx + 4) + window.location.search + window.location.hash);
        }
    </script>
    <meta http-equiv="refresh" content="0; url=../../5.x/okhttp/okhttp3/">
</head>
<body>
    <p>OkHttp 4.x API docs have moved. Redirecting to <a href="../../5.x/okhttp/okhttp3/">5.x API docs</a>&hellip;</p>
</body>
</html>
REDIRECT

# Build the site locally
mkdocs build
