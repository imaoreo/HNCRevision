#!/bin/sh
# Refresh the offline copy of the site's data that ships inside both apps.
set -e
BASE=https://data-security-revision-cards.exquisitemeadow.chatgpt.site
DIR="$(cd "$(dirname "$0")" && pwd)/data"
for f in cards.js quiz.js vlsm.js; do curl -fsSL "$BASE/$f" -o "$DIR/$f"; done
echo "Updated $DIR"
