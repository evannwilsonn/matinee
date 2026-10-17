package matinee

/**
 * The whole experiment, in order: split, baselines, models, tuning on a validation slice of
 * the training data, a check against Spark MLlib, then one evaluation on the held-out test
 * ratings. Writes the JSON files in results/.
 *
 *   sbt "runMain matinee.Experiment"
 */
object Experiment {
  val Seed = 2026L
  val K = 10

  private def log(s: String): Unit = println(s)
  private def f(d: Double) = f"$d%.4f"

  def main(args: Array[String]): Unit = {
    val dir = args.headOption.getOrElse("data/ml-latest-small")
    val ds = Data.load(dir)
    val (train, test) = Data.splitPerUser(ds.ratings, 0.2, Seed)
    val (fit, valid) = Data.splitPerUser(train, 0.2, Seed + 1)
    val nU = ds.numUsers; val nI = ds.numItems
    val fitIdx = new RatingIndex(nU, nI, fit)
    val trainIdx = new RatingIndex(nU, nI, train)

    log(s"${ds.ratings.size} ratings, $nU users, $nI movies; density ${f(ds.ratings.size.toDouble / nU / nI)}")
    log(s"train ${train.size} (fit ${fit.size} + validation ${valid.size}), test ${test.size}")
    log(s"mean rating ${f(trainIdx.mean)}; share of ratings ≥ 4: ${f(train.count(_.value >= 4).toDouble / train.size)}")

    val validRows = Vector.newBuilder[Map[String, Any]]
    def report(step: String, name: String, model: Recommender, rated: Boolean, note: String): (Double, RankScores) = {
      val rmse = if (rated) Metrics.rmse(model, valid) else Double.NaN
      val rs = Metrics.ranking(model, fitIdx, valid, K)
      validRows += Map("step" -> step, "name" -> name, "note" -> note, "rmse" -> rmse,
        "precisionAt10" -> rs.precision, "recallAt10" -> rs.recall, "ndcgAt10" -> rs.ndcg, "coverage" -> rs.coverage)
      log(f"  $name%-44s RMSE ${if (rated) f(rmse) else "   -  "}  P@10 ${f(rs.precision)}  R@10 ${f(rs.recall)}  NDCG@10 ${f(rs.ndcg)}  coverage ${f(rs.coverage)}")
      (rmse, rs)
    }

    // ---- baselines ------------------------------------------------------------------------
    log("\nBaselines (validation)")
    report("baseline", "Global mean", new GlobalMean(fitIdx.mean, nI), rated = true, "Predicts the average rating for everything.")
    val biasLambdas = Seq(1.0, 3.0, 5.0, 10.0, 25.0)
    val biasSearch = biasLambdas.map(l => l -> Metrics.rmse(Biases.fit(fitIdx, l), valid))
    val biasLambda = biasSearch.minBy(_._2)._1
    log(s"  bias model λ search: ${biasSearch.map { case (l, r) => s"$l→${f(r)}" }.mkString(", ")}")
    report("baseline", "User + movie biases", Biases.fit(fitIdx, biasLambda), rated = true, s"μ + b_user + b_movie, λ = $biasLambda.")
    report("baseline", "Most popular", new Popularity(fitIdx), rated = false, "Most-rated movies the user hasn't seen.")

    // ---- explicit ALS -----------------------------------------------------------------------
    log("\nExplicit ALS grid (validation RMSE)")
    val ranks = Seq(5, 10, 20, 40)
    val lambdas = Seq(0.02, 0.05, 0.1, 0.2, 0.5)
    val explicitGrid = for (k <- ranks; l <- lambdas) yield {
      val r = Metrics.rmse(ALS.explicit(fitIdx, k, l), valid)
      log(s"  rank $k, λ $l: ${f(r)}")
      Map[String, Any]("rank" -> k, "lambda" -> l, "rmse" -> r)
    }
    val bestExp = explicitGrid.minBy(_("rmse").asInstanceOf[Double])
    val (eRank, eLambda) = (bestExp("rank").asInstanceOf[Int], bestExp("lambda").asInstanceOf[Double])

    log("\nExplicit ALS on top of the bias model (validation RMSE)")
    val fitBiases = Biases.fit(fitIdx, biasLambda)
    val biasedGrid = for (k <- ranks; l <- lambdas) yield {
      val r = Metrics.rmse(ALS.explicit(fitIdx, k, l, biases = Some(fitBiases)), valid)
      log(s"  rank $k, λ $l: ${f(r)}")
      Map[String, Any]("rank" -> k, "lambda" -> l, "rmse" -> r)
    }
    val bestB = biasedGrid.minBy(_("rmse").asInstanceOf[Double])
    val (bRank, bLambda) = (bestB("rank").asInstanceOf[Int], bestB("lambda").asInstanceOf[Double])

    // ---- implicit ALS ----------------------------------------------------------------------
    log("\nImplicit ALS grid (validation NDCG@10)")
    val implicitGrid = for (k <- Seq(16, 32, 64); l <- Seq(0.01, 0.1, 1.0); a <- Seq(1.0, 10.0, 40.0)) yield {
      val rs = Metrics.ranking(ALS.implicitPrefs(fitIdx, k, l, a), fitIdx, valid, K)
      log(s"  rank $k, λ $l, α $a: NDCG ${f(rs.ndcg)}  P@10 ${f(rs.precision)}")
      Map[String, Any]("rank" -> k, "lambda" -> l, "alpha" -> a, "ndcgAt10" -> rs.ndcg, "precisionAt10" -> rs.precision)
    }
    val bestI = implicitGrid.maxBy(_("ndcgAt10").asInstanceOf[Double])
    val (iRank, iLambda, iAlpha) = (bestI("rank").asInstanceOf[Int], bestI("lambda").asInstanceOf[Double], bestI("alpha").asInstanceOf[Double])

    log("\nModels at their best settings (validation)")
    val expModel = ALS.explicit(fitIdx, eRank, eLambda)
    report("model", "Explicit ALS", expModel, rated = true, s"rank $eRank, λ $eLambda, raw ratings.")
    report("improvement", "Explicit ALS + biases", ALS.explicit(fitIdx, bRank, bLambda, biases = Some(fitBiases)), rated = true,
      s"rank $bRank, λ $bLambda, factorises what the bias model misses.")
    val impModel = ALS.implicitPrefs(fitIdx, iRank, iLambda, iAlpha)
    report("improvement", "Implicit ALS", impModel, rated = false, s"rank $iRank, λ $iLambda, α $iAlpha: who rated what, weighted by stars.")

    // ---- Spark MLlib check ---------------------------------------------------------------
    log("\nCheck: hand-written ALS vs Spark MLlib ALS (same data, settings, 15 iterations)")
    val spark = SparkAls.session()
    val sparkExp = SparkAls.fit(spark, fit, nU, nI, eRank, eLambda, implicitPrefs = false)
    val sparkImp = SparkAls.fit(spark, fit, nU, nI, iRank, iLambda, implicitPrefs = true, alpha = iAlpha)
    spark.stop()
    val mineExpRmse = Metrics.rmse(expModel, valid); val sparkExpRmse = Metrics.rmse(sparkExp, valid)
    val mineImp = Metrics.ranking(impModel, fitIdx, valid, K); val sparkImpRs = Metrics.ranking(sparkImp, fitIdx, valid, K)
    val corr = correlation(valid.map(r => expModel.predict(r.user, r.item)), valid.map(r => sparkExp.predict(r.user, r.item)))
    val overlap = topOverlap(impModel, sparkImp, fitIdx, valid.map(_.user).distinct)
    log(s"  explicit: RMSE mine ${f(mineExpRmse)} vs Spark ${f(sparkExpRmse)}; prediction correlation ${f(corr)}")
    log(s"  implicit: NDCG@10 mine ${f(mineImp.ndcg)} vs Spark ${f(sparkImpRs.ndcg)}; top-10 overlap ${f(overlap)}")

    // ---- final evaluation on test (opened once) ----------------------------------------
    log("\nTest set (train on all training ratings, evaluate once)")
    val finalBiases = Biases.fit(trainIdx, biasLambda)
    val finals = Seq[(String, Recommender, Boolean)](
      ("Global mean", new GlobalMean(trainIdx.mean, nI), true),
      ("User + movie biases", finalBiases, true),
      ("Most popular", new Popularity(trainIdx), false),
      ("Explicit ALS", ALS.explicit(trainIdx, eRank, eLambda), true),
      ("Explicit ALS + biases", ALS.explicit(trainIdx, bRank, bLambda, biases = Some(finalBiases)), true),
      ("Implicit ALS", ALS.implicitPrefs(trainIdx, iRank, iLambda, iAlpha), false),
    )
    val testRows = finals.map { case (name, m, rated) =>
      val rmse = if (rated) Metrics.rmse(m, test) else Double.NaN
      val rs = Metrics.ranking(m, trainIdx, test, K)
      log(f"  $name%-44s RMSE ${if (rated) f(rmse) else "   -  "}  P@10 ${f(rs.precision)}  R@10 ${f(rs.recall)}  NDCG@10 ${f(rs.ndcg)}  coverage ${f(rs.coverage)}  users ${rs.users}")
      Map[String, Any]("name" -> name, "rmse" -> rmse, "precisionAt10" -> rs.precision, "recallAt10" -> rs.recall,
        "ndcgAt10" -> rs.ndcg, "coverage" -> rs.coverage, "users" -> rs.users)
    }

    // ---- write results ----------------------------------------------------------------------
    Json.write("results/validation.json", Json.lines(validRows.result()))
    Json.write("results/test.json", Json.lines(testRows))
    Json.write("results/grid-explicit.json", Json.lines(explicitGrid))
    Json.write("results/grid-explicit-biases.json", Json.lines(biasedGrid))
    Json.write("results/grid-implicit.json", Json.lines(implicitGrid))
    Json.write("results/bias-lambda.json", Json.lines(biasSearch.map { case (l, r) => Map[String, Any]("lambda" -> l, "rmse" -> r) }))
    Json.write("results/spark-check.json", Json.value(Map(
      "explicit" -> Map("rank" -> eRank, "lambda" -> eLambda, "rmseMine" -> mineExpRmse, "rmseSpark" -> sparkExpRmse, "predictionCorrelation" -> corr),
      "implicit" -> Map("rank" -> iRank, "lambda" -> iLambda, "alpha" -> iAlpha, "ndcgMine" -> mineImp.ndcg, "ndcgSpark" -> sparkImpRs.ndcg, "top10Overlap" -> overlap),
    )) + "\n")
    Json.write("results/settings.json", Json.value(Map(
      "seed" -> Seed, "testShare" -> 0.2, "validationShare" -> 0.2, "k" -> K, "relevantAtLeast" -> 4.0,
      "biasLambda" -> biasLambda,
      "explicit" -> Map("rank" -> eRank, "lambda" -> eLambda),
      "explicitWithBiases" -> Map("rank" -> bRank, "lambda" -> bLambda),
      "implicit" -> Map("rank" -> iRank, "lambda" -> iLambda, "alpha" -> iAlpha),
    )) + "\n")
    log("\nWrote results/")
  }

  def correlation(a: Seq[Double], b: Seq[Double]): Double = {
    val ma = a.sum / a.size; val mb = b.sum / b.size
    val cov = a.zip(b).map { case (x, y) => (x - ma) * (y - mb) }.sum
    cov / math.sqrt(a.map(x => (x - ma) * (x - ma)).sum * b.map(y => (y - mb) * (y - mb)).sum)
  }

  /** Average share of the top 10 two models agree on, per user. */
  def topOverlap(a: Recommender, b: Recommender, idx: RatingIndex, users: Seq[Int]): Double = {
    def picks(m: Recommender, u: Int) = {
      val s = m.scores(u); idx.userItems(u).foreach(i => s(i) = Double.NegativeInfinity)
      Metrics.topK(s, K).toSet
    }
    users.map(u => (picks(a, u) intersect picks(b, u)).size / K.toDouble).sum / users.size
  }
}
