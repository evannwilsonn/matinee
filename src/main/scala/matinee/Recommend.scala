package matinee

import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._

/**
 * The deployed part: a Spark batch job. It trains implicit ALS on every rating with the
 * settings the experiment picked, scores every movie for every user, drops what each user has
 * already rated, and writes each user's top N to output/top-picks/ as CSV.
 *
 * It also exports the movie factors the browser demo uses (web/model.json), so the demo
 * scores with exactly the model this job ran.
 *
 *   sbt "runMain matinee.Recommend"            (top 10)
 *   sbt "runMain matinee.Recommend 20"         (top 20)
 */
object Recommend {

  def main(args: Array[String]): Unit = {
    val topN = args.headOption.map(_.toInt).getOrElse(10)
    val dir = "data/ml-latest-small"
    val settings = Settings.load("results/settings.json")
    val ds = Data.load(dir)

    val spark = SparkAls.session()
    import spark.implicits._

    val ratings = ds.ratings.map(r => (r.user, r.item, r.value.toFloat)).toDF("user", "item", "rating").cache()
    val model = new ALS()
      .setUserCol("user").setItemCol("item").setRatingCol("rating")
      .setImplicitPrefs(true).setRank(settings.rank).setRegParam(settings.lambda).setAlpha(settings.alpha)
      .setMaxIter(15).setSeed(Experiment.Seed)
      .fit(ratings)

    val movies = ds.movies.zipWithIndex.map { case (m, i) => (i, m.id, m.title + m.year.fold("")(y => s" ($y)")) }
      .toDF("item", "movieId", "title")
    val users = ds.userIds.zipWithIndex.map { case (id, u) => (u, id) }.toDF("user", "userId")

    // Score every (user, movie) pair, remove movies the user already rated, keep the best N.
    val dotUdf = udf((a: Seq[Float], b: Seq[Float]) => a.zip(b).map { case (x, y) => x * y }.sum)
    val u = model.userFactors.withColumnRenamed("id", "user").withColumnRenamed("features", "uf")
    val i = model.itemFactors.withColumnRenamed("id", "item").withColumnRenamed("features", "if")
    val byScore = Window.partitionBy("user").orderBy(desc("score"), asc("item"))
    val picks = u.crossJoin(i)
      .withColumn("score", dotUdf($"uf", $"if"))
      .join(ratings.select("user", "item"), Seq("user", "item"), "left_anti")
      .withColumn("rank", row_number().over(byScore))
      .where($"rank" <= topN)
      .join(users, "user").join(movies, "item")
      .select($"userId", $"rank", $"movieId", $"title", round($"score", 4).as("score"))
      .orderBy("userId", "rank")
      .cache()

    picks.coalesce(1).write.mode("overwrite").option("header", "true").csv("output/top-picks")
    val nUsers = picks.select("userId").distinct().count()
    println(s"Wrote ${picks.count()} picks for $nUsers users to output/top-picks/")
    picks.where($"userId" === ds.userIds.head).show(topN, truncate = false)

    // ---- export for the browser demo -----------------------------------------------------
    val factors = model.itemFactors.as[(Int, Array[Float])].collect().toMap
    val k = settings.rank
    val counts = ds.ratings.groupBy(_.item).view.mapValues(_.size).toMap
    val all = (0 until ds.numItems).map(it => factors.getOrElse(it, Array.fill(k)(0f)).map(_.toDouble)).toArray
    val gram = Linalg.gram(all, k) // YᵀY over the whole catalogue, which the fold-in needs
    val minRatings = 20
    val keep = (0 until ds.numItems).filter(it => counts.getOrElse(it, 0) >= minRatings)
      .sortBy(it => -counts(it))
    val demo = Map(
      "rank" -> k, "lambda" -> settings.lambda, "alpha" -> settings.alpha,
      "trainedOn" -> Map("ratings" -> ds.ratings.size, "users" -> ds.numUsers, "movies" -> ds.numItems),
      "minRatings" -> minRatings,
      "gram" -> gram.map(g => Json.num(g, 5).toDouble),
      "movies" -> keep.map { it =>
        val m = ds.movies(it)
        Map("id" -> m.id, "t" -> m.title, "y" -> m.year, "g" -> m.genres.mkString("|"), "n" -> counts(it),
          "f" -> all(it).map(x => Json.num(x, 3).toDouble))
      },
    )
    Json.write("web/model.json", Json.value(demo))
    println(s"Exported ${keep.size} movies (rated by $minRatings+ people) to web/model.json")
    spark.stop()
  }
}

/** The implicit-ALS settings the experiment chose, read back from results/settings.json. */
final case class Settings(rank: Int, lambda: Double, alpha: Double)

object Settings {
  def load(path: String): Settings = {
    val text = scala.io.Source.fromFile(path, "UTF-8").mkString
    val block = """"implicit":\{([^}]*)\}""".r.findFirstMatchIn(text).map(_.group(1))
      .getOrElse(sys.error(s"No implicit settings in $path; run matinee.Experiment first"))
    def field(name: String) = s""""$name":([0-9.eE+-]+)""".r.findFirstMatchIn(block).map(_.group(1).toDouble)
      .getOrElse(sys.error(s"Missing $name in $path"))
    Settings(field("rank").toInt, field("lambda"), field("alpha"))
  }
}
