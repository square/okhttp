#!/bin/bash

set -ex

REPO="git@github.com:square/okhttp.git"
GROUP_ID="com.squareup.okhttp"
ARTIFACT_ID="okhttp"

DIR=temp-clone

# Delete any existing temporary website clone
rm -rf $DIR

# Clone the current repo into temp folder
git clone $REPO $DIR

# Move working directory into temp folder
cd $DIR

# Checkout and track the gh-pages branch
git checkout -t origin/gh-pages

# Delete everything
rm -rf *

# Copy website files from real repo
cp -R ../website/* .

# Download the latest javadoc
curl -L "http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=$GROUP_ID&a=$ARTIFACT_ID&v=LATEST&c=javadoc" > javadoc.zip
mkdir javadoc
unzip javadoc.zip -d javadoc
rm javadoc.zip

# Stage all files in git and create a commit
git add .
git add -u
git commit -m "Website at $(date)"

# Push the new files up to GitHub
git push origin gh-pages

# Delete our temp folder
cd ..
rm -rf $DIR
