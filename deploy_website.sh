#!/bin/bash

set -ex

REPO="git@github.com:square/okhttp.git"
DIR=temp-clone

# Delete any existing temporary website clone
rm -rf $DIR

# Clone the current repo into temp folder
git clone $REPO $DIR

# Move working directory into temp folder
cd $DIR

# Checkout and track the gh-pages branch
git checkout -t origin/gh-pages

# Delete everything that isn't versioned (1.x, 2.x)
ls | grep -E -v '^\d+\.x$' | xargs rm -rf

# Copy website files from real repo
cp -R ../website/* .

# Stage all files in git and create a commit
git add .
git add -u
git commit -m "Website at $(date)"

# Push the new files up to GitHub
git push origin gh-pages

# Delete our temp folder
cd ..
rm -rf $DIR
