#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material mkdocs-redirects

set -ex

# Generate the API docs
./gradlew \
  :mockwebserver:dokkaGfm \
  :okhttp-brotli:dokkaGfm \
  :okhttp-dnsoverhttps:dokkaGfm \
  :logging-interceptor:dokkaGfm \
  :okhttp-sse:dokkaGfm \
  :okhttp-tls:dokkaGfm \
  :okhttp-urlconnection:dokkaGfm \
  :okhttp:dokkaGfm

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
for MARKDOWN_FILE in $(find docs/4.x -name '*.md'); do
  echo $MARKDOWN_FILE
  title_markdown_file $MARKDOWN_FILE
done
set -x

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelogs/changelog.md
cp CONTRIBUTING.md docs/contribute/contributing.md

# Build the site locally
mkdocs build
