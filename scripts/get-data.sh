#!/usr/bin/env sh
# Downloads MovieLens latest-small into data/ (not committed; see data/README.md).
set -e
cd "$(dirname "$0")/.."
mkdir -p data
curl -fsSL -o data/ml-latest-small.zip https://files.grouplens.org/datasets/movielens/ml-latest-small.zip
unzip -oq data/ml-latest-small.zip -d data
rm data/ml-latest-small.zip
echo "Saved data/ml-latest-small/ ($(wc -l < data/ml-latest-small/ratings.csv) lines in ratings.csv)"
