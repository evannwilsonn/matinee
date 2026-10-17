# Data

[MovieLens latest-small](https://grouplens.org/datasets/movielens/latest/) from GroupLens Research: 100,836 ratings (0.5 to 5 stars) of 9,742 films by 610 users, collected between 1996 and 2018.

The raw files aren't committed here. Download them with:

```sh
./scripts/get-data.sh        # macOS / Linux
./scripts/get-data.ps1       # Windows PowerShell
```

That creates `data/ml-latest-small/` with `ratings.csv` and `movies.csv`, which are the two files the code reads.

**License.** GroupLens allows research use and redistribution of the data and of transformations of it, under the same terms, for non-commercial purposes only. The film factors in `web/model.json` and the demo page are such a transformation, and they're shared under those terms.

**Citation.** F. Maxwell Harper and Joseph A. Konstan. 2015. The MovieLens Datasets: History and Context. ACM Transactions on Interactive Intelligent Systems (TiiS) 5, 4: 19:1–19:19. https://doi.org/10.1145/2827872
