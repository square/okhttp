#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material

set -ex

REPO="git@github.com:square/okhttp.git"
DIR=temp-clone
USAGE="Usage: ./deploy_website.sh [-d]"
# DRY_RUN runs the build on the repo directly, not in a temp-clone
DRY_RUN=""

while getopts "d" opt; do
    case ${opt} in
        d ) DRY_RUN="TRUE"
        ;;
        \? )
            echo "Invalid option: $OPTARG" 1>&2
            echo "$USAGE"
            exit 1
        ;;
        : )
            echo "Invalid option: $OPTARG requires an argument" 1>&2
            exit 1
        ;;
    esac
done
shift $((OPTIND -1))

if [ -z "$DRY_RUN" ]; then
    # Delete any existing temporary website clone
    rm -rf $DIR
    
    # Clone the current repo into temp folder
    git clone $REPO $DIR
    
    # Move working directory into temp folder
    cd $DIR
fi

# Generate the API docs
./gradlew \
:mockwebserver:dokka \
:okhttp-brotli:dokka \
:okhttp-dnsoverhttps:dokka \
:okhttp-logging-interceptor:dokka \
:okhttp-sse:dokka \
:okhttp-tls:dokka \
:okhttp-urlconnection:dokka \
:okhttp:dokka

# Dokka filenames like `-http-url/index.md` don't work well with MkDocs <title> tags.
# Assign metadata to the file's first Markdown heading.
# https://www.mkdocs.org/user-guide/writing-your-docs/#meta-data
title_markdown_file() {
    TITLE_PATTERN="s/^[#]+ *(.*)/title: \1 - OkHttp/"
    echo "---"                                                     > "$1.fixed"
    cat $1 | sed -E "$TITLE_PATTERN" | grep "title: " | head -n 1 >> "$1.fixed"
    echo "---"                                                    >> "$1.fixed"
    echo                                                          >> "$1.fixed"
    cat $1                                                        >> "$1.fixed"
    mv "$1.fixed" "$1"
}

set +x
for MARKDOWN_FILE in $(find docs/4.x/ -name '*.md'); do
    echo $MARKDOWN_FILE
    title_markdown_file $MARKDOWN_FILE
done
set -x

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelog.md
cp CONTRIBUTING.md docs/contributing.md

if [ -z "$DRY_RUN" ]; then
    git checkout gh-pages
    
    # else
    # See if this needs to be added
    # git remote set-url origin https://github.com/square/okhttp.git
    # git config --global user.email "circleci@squareup.com"
    # git config --global user.name "CircleCI"
fi

# Restore Javadocs from 1.x, 2.x, and 3.x.
git cherry-pick bb229b9dcc9a21a73edbf8d936bea88f52e0a3ff
git cherry-pick c695732f1d4aea103b826876c077fbfea630e244

if [ -z "$DRY_RUN" ]; then
    git push
fi

if [ -z "$DRY_RUN" ]; then
    # Build the site and push the new files up to GitHub
    mkdocs gh-deploy
else
    # Just build the site
    mkdocs build
fi

if [ -z "$DRY_RUN" ]; then
    # Delete our temp folder
    cd ..
    rm -rf $DIR
fi
