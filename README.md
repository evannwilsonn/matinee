# Matinee

A movie recommender in Scala. Alternating least squares (ALS) is written from scratch, checked against Spark MLlib, and shipped as a Spark batch job that writes every user's top 10.

**[Try the demo](https://evannwilsonn.github.io/matinee/)**: rate a few films and get ten picks, each with the rating of yours that pulled it in. It runs in your browser with the same model the batch job trained.

On held-out MovieLens ratings, the deployed model's top 10 contains a film the user rated 4+ stars **74% more often than recommending the most popular films** (precision@10 0.204 vs 0.117). It also spreads its picks across 13× more of the catalogue.

The project follows seven steps: problem, data, baseline, model, evaluation, improvement and deployment.

---

## 1. Problem

Given what each person has rated, choose ten films they haven't seen that they're likely to enjoy. There are two ways to frame that, and this project tries both:

- **Predict the star rating** for every unseen film, then recommend the highest. Measured by **RMSE** on held-out ratings.
- **Rank directly.** Measured by what actually gets shown: of the 10 picks, how many are films the user rated 4 stars or more in their hidden ratings. That's **precision@10**, plus **recall@10** and **NDCG@10**, which rewards a hit at #1 more than a hit at #10.

**The deciding metric is NDCG@10**, because the deliverable is a ranked top 10, not a rating. RMSE is reported because it's the classic metric, and because the gap between the two turns out to be the main finding.

## 2. Data

[MovieLens latest-small](data/README.md): 100,836 ratings from 0.5 to 5 stars, by 610 users, of 9,724 films. The matrix is 98.3% empty, and 48% of ratings are 4 stars or more.

- **The split is per user.** Each user has a random 20% of their ratings held out as the test set (seed 2026), giving 80,672 training and 20,164 test ratings. Splitting within each user mirrors the real job: recommend for people the system already knows.
- **Tuning uses a validation set.** Another 20% of each user's training ratings is held out for that. The test set is used once, at the end.
- **Ranking candidates are every film the user hasn't rated in training**, about 9,700 per user, not a short list. 601 users have at least one 4+ star film in their test ratings and are scored.
- **Unseen films.** A film with no training ratings has nothing to learn from. For rating prediction it falls back to the global mean; for ranking it scores 0.

The raw data isn't committed. `scripts/get-data.sh` downloads it.

## 3. Baseline

Three baselines, scored on the validation ratings:

| Baseline | RMSE | Precision@10 | NDCG@10 |
|---|---|---|---|
| Global mean (3.50 stars for everything) | 1.046 | 0.012 | 0.015 |
| User + movie biases: μ + b_user + b_movie, shrunk by λ = 3 | **0.870** | 0.022 | 0.025 |
| Most popular: most-rated films the user hasn't rated | – | **0.087** | **0.125** |

The bias model is the bar for RMSE and popularity is the bar for picks. They're very different bars: the bias model is a strong rating predictor and a terrible recommender. It gives nearly everyone the same short list of the highest-rated films, and most of those were rated by only 5 to 20 people (Shawshank is the exception).

## 4. Model

**ALS written from scratch** ([`Models.scala`](src/main/scala/matinee/Models.scala)). Each user and film becomes a vector, and a rating is modelled as their dot product. ALS fixes the film vectors and solves every user's vector exactly (a small ridge regression, solved by Cholesky), then does the reverse, and repeats. Both halves run in parallel over users or films.

- **Explicit ALS** fits the star ratings directly. It uses the ALS-WR objective that Spark uses: minimise Σ(r − x·y)² + λ(Σ n_u|x_u|² + Σ n_i|y_i|²).
- **Implicit ALS** (Hu, Koren and Volinsky 2008) ignores the size of the rating and fits *whether* a user rated a film. Rated films get preference 1 with confidence 1 + α·stars. Every unrated film is a weak "no". A YᵀY shortcut keeps each user's solve proportional to the films they rated, not to all 9,700.

**Checked against Spark MLlib.** Spark's `ALS` was run on the same data with the same settings:

| | Hand-written | Spark MLlib |
|---|---|---|
| Explicit, validation RMSE | 0.9045 | 0.9034 (predictions correlate at 0.993) |
| Implicit, validation NDCG@10 | 0.1839 | 0.1808 |

The implicit models share 69% of each user's top 10. The objective isn't convex, so different random starting points converge to different, equally good factorisations; ranking quality matches even where the exact picks differ.

**Tuning on validation.**
- Explicit ALS used rank ∈ {5, 10, 20, 40} and λ ∈ {0.02 … 0.5}, picked by RMSE. Rank 10 with λ = 0.2 won.
- Implicit ALS used rank ∈ {16, 32, 64}, λ ∈ {0.01, 0.1, 1} and α ∈ {1, 10, 40}, picked by NDCG@10. Rank 64, λ = 0.1 and α = 1 won.

Full grids are in [`results/`](results).

## 5. Evaluation

Validation results at the best settings:

| Validation | RMSE | Precision@10 | Recall@10 | NDCG@10 | Catalogue covered |
|---|---|---|---|---|---|
| User + movie biases | 0.870 | 0.022 | 0.023 | 0.025 | 0.2% |
| Most popular | – | 0.087 | 0.101 | 0.125 | 0.4% |
| Explicit ALS | 0.905 | 0.001 | 0.001 | 0.000 | 2.1% |

**Plain explicit ALS lost on both counts.** Its RMSE is worse than the bias model's: it spends its factors on what biases capture for free (some users rate everything high, some films are just liked). Its top 10 almost never contains a film the user went on to rate highly. Films with only a handful of ratings get poorly pinned-down vectors, and some land with high predicted ratings.

## 6. Improvement

Two changes, both chosen on validation before the test set was opened:

| Validation | RMSE | NDCG@10 |
|---|---|---|
| Explicit ALS | 0.905 | 0.000 |
| + factorise what the bias model leaves over (rank 40, λ 0.2) | **0.861** | 0.019 |
| Implicit ALS instead (rank 64, λ 0.1, α 1) | – | **0.184** |

What changed and why:

1. **Biases first, factors second.** The bias model takes out each user's generosity and each film's general appeal, and ALS factorises what's left. That beats the bias baseline on RMSE (0.861 vs 0.870), but it's still a poor recommender.
2. **Change what's being predicted.** For picking films, *whether* someone watched a film carries more signal than how many stars they gave it. Which films a person rates is itself a strong statement of taste. Implicit ALS beats popularity by 47% on validation NDCG@10. The best α was the smallest one tried (1): stars help a little, but the main signal is which films were rated at all.

**Test set**, 601 users, each model refitted on all training ratings and scored once:

| Test | RMSE | Precision@10 | Recall@10 | NDCG@10 | Catalogue covered |
|---|---|---|---|---|---|
| Global mean | 1.051 | 0.014 | 0.011 | 0.015 | 0.2% |
| User + movie biases | 0.868 | 0.021 | 0.018 | 0.021 | 0.2% |
| Most popular | – | 0.117 | 0.095 | 0.155 | 0.6% |
| Explicit ALS | 0.897 | 0.000 | 0.000 | 0.000 | 1.9% |
| Explicit ALS + biases | **0.854** | 0.018 | 0.012 | 0.016 | 2.5% |
| **Implicit ALS (deployed)** | – | **0.204** | **0.202** | **0.273** | **8.2%** |

The test numbers tell the same story as validation:

- **Best rating model:** explicit ALS + biases, 0.013 RMSE better than the bias baseline.
- **Best recommender:** implicit ALS, with NDCG@10 of 0.273 against 0.155 for popularity (+76%). It also recommends from 13× more of the catalogue, so it isn't just handing everyone the same blockbusters.

Holding out random ratings flatters popular films, because popular films are the ones people rate. That makes popularity a tougher bar here than it would be in a live test.

The whole experiment runs with one command, takes about 3 minutes, and writes [`results/`](results):

```sh
./scripts/get-data.sh
sbt "runMain matinee.Experiment"
```

## 7. Deployment

**A Spark batch job** ([`Recommend.scala`](src/main/scala/matinee/Recommend.scala)) does four things:

1. Trains Spark MLlib's implicit ALS on every rating, using the settings the experiment chose (read from `results/settings.json`).
2. Scores every user against every film.
3. Removes films each user has already rated.
4. Writes each user's top N as CSV.

```sh
sbt "runMain matinee.Recommend"        # top 10 per user → output/top-picks/
sbt "runMain matinee.Recommend 20"     # top 20
```

```
userId,rank,movieId,title,score
1,1,1242,Glory (1989),0.9964
1,2,2804,"Christmas Story, A (1983)",0.9583
1,3,1036,Die Hard (1988),0.947
```

It writes 6,100 picks for 610 users in under a minute on a laptop. The same job exports the film vectors to `web/model.json` for the demo.

**Browser demo** ([`docs/`](docs), on GitHub Pages):

- **Same maths as the trainer.** When you rate films, the page solves for your vector with the same equation as `ALS.implicitSolve`, in JavaScript, then ranks every film.
- **Exact explanations.** A pick's score is a sum of one term per film you rated, so "mostly because you rated X" is exact, not a guess.
- **Coverage.** It offers the 1,297 films that at least 20 people rated.
- **Rebuilding.** `python3 scripts/build-demo.py` builds it.

## Tests

```sh
sbt test   # 12 tests
```

What they check:

- **Data:** CSV parsing of quoted titles, and the per-user split keeping every rating exactly once.
- **Solvers:** the Cholesky solve on a hand-checked system; the bias model recovering known effects; and explicit ALS rebuilding a rank-2 matrix.
- **Implicit solve:** the YᵀY shortcut matching the full normal equations.
- **Unseen films:** the fallback for films nobody rated.
- **Metrics:** precision, recall and NDCG on a hand-worked example.
- **Settings:** reading the chosen settings back for the batch job.

## Layout

```
src/main/scala/matinee/
  Data.scala         loading MovieLens, per-user split
  Models.scala       baselines, explicit and implicit ALS, Cholesky
  Metrics.scala      RMSE, precision/recall/NDCG@k, coverage
  SparkAls.scala     Spark MLlib ALS, unpacked for comparison
  Experiment.scala   baselines → models → tuning → Spark check → one test evaluation
  Recommend.scala    the Spark batch job, plus the demo export
src/test/            ScalaTest
web/, docs/          demo template and the built GitHub Pages site
results/             validation, grid, Spark-check and test metrics
```

Scala 2.13 · Spark 4.0 · Java 17+ · sbt

Data: F. Maxwell Harper and Joseph A. Konstan, "The MovieLens Datasets: History and Context", ACM TiiS 5(4), 2015. Used under the GroupLens terms (non-commercial).
