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

# Download the latest javadoc to directories like 'javadoc' or 'javadoc-urlconnection'.
for DOCUMENTED_ARTIFACT in okhttp okhttp-urlconnection okhttp-apache
do
  curl -L "https://search.maven.org/remote_content?g=$GROUP_ID&a=$DOCUMENTED_ARTIFACT&v=LATEST&c=javadoc" > javadoc.zip
  JAVADOC_DIR="javadoc${DOCUMENTED_ARTIFACT//okhttp/}"
  mkdir $JAVADOC_DIR
  unzip javadoc.zip -d $JAVADOC_DIR
  rm javadoc.zip
done

# Download the 1.6.0 javadoc to '1.x/javadoc'.
curl -L "https://search.maven.org/remote_content?g=$GROUP_ID&a=$ARTIFACT_ID&v=1.6.0&c=javadoc" > javadoc.zip
mkdir -p 1.x/javadoc
unzip javadoc.zip -d 1.x/javadoc
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
