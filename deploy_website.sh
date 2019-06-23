#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material

set -ex

REPO="git@github.com:square/okhttp.git"
DIR=temp-clone

# Delete any existing temporary website clone
rm -rf $DIR

# Clone the current repo into temp folder
git clone $REPO $DIR

# Move working directory into temp folder
cd $DIR

# Generate the API docs
./gradlew \
  :mockwebserver:dokka \
  :okhttp-dnsoverhttps:dokka \
  :okhttp-logging-interceptor:dokka \
  :okhttp-sse:dokka \
  :okhttp-tls:dokka \
  :okhttp-urlconnection:dokka \
  :okhttp:dokka

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelog.md
cp CONTRIBUTING.md docs/contributing.md

# Build the site and push the new files up to GitHub
mkdocs gh-deploy

# Restore Javadocs from 1.x, 2.x, and 3.x.
git checkout gh-pages
git cherry-pick bb229b9dcc9a21a73edbf8d936bea88f52e0a3ff
git cherry-pick c695732f1d4aea103b826876c077fbfea630e244
git push

# Delete our temp folder
cd ..
rm -rf $DIR
