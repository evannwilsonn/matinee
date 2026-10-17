package matinee

import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.sql.SparkSession

/** Spark MLlib's ALS on the same dense ids, unpacked into plain factor arrays for comparison. */
object SparkAls {

  def session(): SparkSession = {
    val spark = SparkSession.builder().master("local[*]").appName("matinee")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "8")
      .getOrCreate()
    spark.sparkContext.setLogLevel("ERROR")
    spark
  }

  def fit(spark: SparkSession, ratings: Seq[Rating], numUsers: Int, numItems: Int, rank: Int, lambda: Double,
          implicitPrefs: Boolean, alpha: Double = 1.0, iterations: Int = 15, seed: Long = 1): Factors = {
    import spark.implicits._
    val df = ratings.map(r => (r.user, r.item, r.value.toFloat)).toDF("user", "item", "rating")
    val model = new ALS()
      .setUserCol("user").setItemCol("item").setRatingCol("rating")
      .setRank(rank).setRegParam(lambda).setMaxIter(iterations).setSeed(seed)
      .setImplicitPrefs(implicitPrefs).setAlpha(alpha)
      .fit(df)
    def unpack(rows: Array[(Int, Array[Float])], n: Int) = {
      val out = Array.fill(n)(new Array[Double](rank))
      rows.foreach { case (id, f) => out(id) = f.map(_.toDouble) }
      out
    }
    val users = unpack(model.userFactors.as[(Int, Array[Float])].collect(), numUsers)
    val items = unpack(model.itemFactors.as[(Int, Array[Float])].collect(), numItems)
    new Factors(users, items, None, coldStart = if (implicitPrefs) Double.NaN else ratings.map(_.value).sum / ratings.size)
  }
}
