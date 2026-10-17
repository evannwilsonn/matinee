package matinee

import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

class DataSpec extends AnyFunSuite {
  test("CSV splitting honours quotes and escaped quotes") {
    assert(Data.splitCsv("""11,"American President, The (1995)",Comedy|Drama|Romance""") ==
      Vector("11", "American President, The (1995)", "Comedy|Drama|Romance"))
    assert(Data.splitCsv("""1,"Say ""Hi"" (2001)",x""") == Vector("1", "Say \"Hi\" (2001)", "x"))
  }

  test("titles lose their year into a separate field") {
    assert(Data.parseTitle("Heat (1995)") == ("Heat", Some(1995)))
    assert(Data.parseTitle("Babylon 5") == ("Babylon 5", None))
    assert(Data.parseTitle("1984 (1984)") == ("1984", Some(1984)))
  }

  test("per-user split keeps every rating exactly once and holds out ~20% of each user") {
    val rng = new Random(4)
    val ratings = for (u <- 0 until 30; i <- rng.shuffle((0 until 200).toList).take(20 + u)) yield Rating(u, i, 1 + rng.nextInt(5))
    val (train, test) = Data.splitPerUser(ratings, 0.2, 7)
    assert((train ++ test).sortBy(r => (r.user, r.item)) == ratings.sortBy(r => (r.user, r.item)))
    for (u <- 0 until 30) {
      val n = 20 + u
      assert(test.count(_.user == u) == math.round(n * 0.2).toInt)
    }
  }
}

class LinalgSpec extends AnyFunSuite {
  test("Cholesky solve matches a hand-checked system") {
    // [[4,2],[2,3]] x = [2,5]  gives  x = [-0.5, 2]
    val x = Linalg.solveSpd(Array(4.0, 2, 2, 3), Array(2.0, 5), 2)
    assert(math.abs(x(0) + 0.5) < 1e-12 && math.abs(x(1) - 2) < 1e-12)
  }
}

class ModelSpec extends AnyFunSuite {
  private def idx(rs: Seq[Rating], nU: Int, nI: Int) = new RatingIndex(nU, nI, rs.toIndexedSeq)

  test("the bias model recovers additive user and movie effects") {
    val ub = Array(-1.0, 0.0, 1.0); val ib = Array(0.5, -0.5, 0.0, 1.0)
    val rs = for (u <- 0 until 3; i <- 0 until 4) yield Rating(u, i, 3 + ub(u) + ib(i))
    val m = Biases.fit(idx(rs, 3, 4), lambda = 1e-9, passes = 200)
    for (r <- rs) assert(math.abs(m.predict(r.user, r.item) - r.value) < 1e-6)
  }

  test("explicit ALS reconstructs a fully observed rank-2 matrix") {
    val rng = new Random(1)
    val u = Array.fill(15)(Array.fill(2)(rng.nextGaussian()))
    val v = Array.fill(12)(Array.fill(2)(rng.nextGaussian()))
    val rs = for (a <- 0 until 15; b <- 0 until 12) yield Rating(a, b, Linalg.dot(u(a), v(b)))
    val m = ALS.explicit(idx(rs, 15, 12), rank = 2, lambda = 1e-6, iterations = 200)
    val err = math.sqrt(rs.map(r => math.pow(m.predict(r.user, r.item) - r.value, 2)).sum / rs.size)
    assert(err < 1e-3, s"RMSE $err")
  }

  test("the YᵀY shortcut gives the same implicit solve as the full normal equations") {
    val rng = new Random(9)
    val k = 4; val nItems = 30; val lambda = 0.3; val alpha = 2.0
    val y = Array.fill(nItems)(Array.fill(k)(rng.nextGaussian()))
    val ids = Array(3, 7, 8, 20, 25); val stars = Array(5.0, 3.5, 4.0, 1.0, 2.5)
    val fast = ALS.implicitSolve(y, Linalg.gram(y, k), ids, stars, k, lambda, alpha)

    // Naive version: confidence 1 + α·r for rated movies, 1 elsewhere; preference 1 only where rated.
    val m = new Array[Double](k * k); val b = new Array[Double](k)
    for (i <- 0 until nItems) {
      val j = ids.indexOf(i)
      val c = if (j >= 0) 1 + alpha * stars(j) else 1.0
      val p = if (j >= 0) 1.0 else 0.0
      for (a <- 0 until k) { b(a) += c * p * y(i)(a); for (bb <- 0 until k) m(a * k + bb) += c * y(i)(a) * y(i)(bb) }
    }
    for (a <- 0 until k) m(a * k + a) += lambda * ids.length
    val slow = Linalg.solveSpd(m, b, k)
    fast.zip(slow).foreach { case (f, s) => assert(math.abs(f - s) < 1e-10) }
  }

  test("a movie nobody rated falls back to the mean for ratings and sinks in rankings") {
    val rs = Seq(Rating(0, 0, 5), Rating(0, 1, 3), Rating(1, 0, 4), Rating(1, 1, 2))
    val m = ALS.explicit(idx(rs, 2, 3), rank = 1, lambda = 0.1)
    assert(m.predict(0, 2) == 3.5)
    assert(m.scores(0)(2) == 0.0)
  }
}

class MetricsSpec extends AnyFunSuite {
  test("topK returns the k best, best first") {
    assert(Metrics.topK(Array(0.1, 0.9, 0.5, 0.7, Double.NegativeInfinity), 3).toSeq == Seq(1, 3, 2))
  }

  /** A fixed ranking: item i scores −i, so the order is 0, 1, 2, … */
  private object Fixed extends Recommender {
    def predict(u: Int, i: Int): Double = -i
    def scores(u: Int): Array[Double] = Array.tabulate(20)(i => -i.toDouble)
  }

  test("ranking metrics on a hand-worked example, with training items excluded") {
    val train = new RatingIndex(1, 20, Vector(Rating(0, 0, 5))) // item 0 is seen, so the top 10 is items 1–10
    val heldOut = Seq(Rating(0, 1, 5), Rating(0, 3, 4), Rating(0, 15, 5), Rating(0, 2, 2)) // relevant: 1, 3, 15
    val s = Metrics.ranking(Fixed, train, heldOut, k = 10)
    assert(math.abs(s.precision - 0.2) < 1e-12)
    assert(math.abs(s.recall - 2.0 / 3) < 1e-12)
    val dcg = 1 / log2(2) + 1 / log2(4)      // hits at positions 1 and 3
    val idcg = 1 / log2(2) + 1 / log2(3) + 1 / log2(4)
    assert(math.abs(s.ndcg - dcg / idcg) < 1e-12)
    assert(s.users == 1)
  }

  test("RMSE clips predictions to the star range") {
    object Wild extends Recommender { def predict(u: Int, i: Int) = 9.0; def scores(u: Int) = Array(9.0) }
    assert(Metrics.rmse(Wild, Seq(Rating(0, 0, 4))) == 1.0)
  }

  private def log2(x: Double) = math.log(x) / math.log(2)
}

class SettingsSpec extends AnyFunSuite {
  test("reads the implicit settings back from the results file") {
    val f = java.io.File.createTempFile("settings", ".json")
    Json.write(f.getPath, Json.value(Map("explicit" -> Map("rank" -> 10, "lambda" -> 0.2),
      "implicit" -> Map("rank" -> 64, "lambda" -> 0.1, "alpha" -> 1.0))))
    assert(Settings.load(f.getPath) == Settings(64, 0.1, 1.0))
  }
}
