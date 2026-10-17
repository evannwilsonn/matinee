package matinee

import java.util.stream.IntStream
import scala.util.Random

/** Anything that can score a movie for a user. Higher means "recommend sooner". */
trait Recommender {
  def predict(user: Int, item: Int): Double
  /** A score for every item, for ranking. */
  def scores(user: Int): Array[Double]
}

/** Ratings grouped by user and by item, the shape ALS works on. */
final class RatingIndex(val numUsers: Int, val numItems: Int, ratings: IndexedSeq[Rating]) {
  private def group(key: Rating => Int, other: Rating => Int, n: Int): (Array[Array[Int]], Array[Array[Double]]) = {
    val buckets = Array.fill(n)(Vector.newBuilder[Rating])
    ratings.foreach(r => buckets(key(r)) += r)
    val built = buckets.map(_.result())
    (built.map(_.map(other).toArray), built.map(_.map(_.value).toArray))
  }
  val (userItems, userValues) = group(_.user, _.item, numUsers)
  val (itemUsers, itemValues) = group(_.item, _.user, numItems)
  val mean: Double = if (ratings.isEmpty) 0 else ratings.map(_.value).sum / ratings.size
}

object Linalg {
  /** Solves A x = b for a symmetric positive definite k×k matrix stored row-major. */
  def solveSpd(a: Array[Double], b: Array[Double], k: Int): Array[Double] = {
    val l = new Array[Double](k * k)
    var i = 0
    while (i < k) {
      var j = 0
      while (j <= i) {
        var sum = a(i * k + j)
        var p = 0
        while (p < j) { sum -= l(i * k + p) * l(j * k + p); p += 1 }
        if (i == j) {
          require(sum > 0, "matrix is not positive definite; raise the regularization")
          l(i * k + i) = math.sqrt(sum)
        } else l(i * k + j) = sum / l(j * k + j)
        j += 1
      }
      i += 1
    }
    val y = new Array[Double](k)
    i = 0
    while (i < k) { var s = b(i); var p = 0; while (p < i) { s -= l(i * k + p) * y(p); p += 1 }; y(i) = s / l(i * k + i); i += 1 }
    val x = new Array[Double](k)
    i = k - 1
    while (i >= 0) { var s = y(i); var p = i + 1; while (p < k) { s -= l(p * k + i) * x(p); p += 1 }; x(i) = s / l(i * k + i); i -= 1 }
    x
  }

  def dot(a: Array[Double], b: Array[Double]): Double = { var s = 0.0; var i = 0; while (i < a.length) { s += a(i) * b(i); i += 1 }; s }

  /** YᵀY for a list of k-vectors. */
  def gram(rows: Array[Array[Double]], k: Int): Array[Double] = {
    val g = new Array[Double](k * k)
    rows.foreach { y => var i = 0; while (i < k) { val yi = y(i); var j = 0; while (j < k) { g(i * k + j) += yi * y(j); j += 1 }; i += 1 } }
    g
  }
}

// ---- baselines -------------------------------------------------------------------------

final class GlobalMean(mu: Double, numItems: Int) extends Recommender {
  def predict(user: Int, item: Int): Double = mu
  def scores(user: Int): Array[Double] = Array.fill(numItems)(mu)
}

/** Most-rated first. It has no notion of a predicted rating, only an order. */
final class Popularity(idx: RatingIndex) extends Recommender {
  private val counts = idx.itemUsers.map(_.length.toDouble)
  def predict(user: Int, item: Int): Double = idx.mean
  def scores(user: Int): Array[Double] = counts.clone()
}

/**
 * r̂ = μ + b_u + b_i, with the biases shrunk towards zero by λ. Fitted by alternating the two
 * closed-form updates, which converges in a handful of passes.
 */
final class Biases(val mu: Double, val userBias: Array[Double], val itemBias: Array[Double]) extends Recommender {
  def predict(user: Int, item: Int): Double = mu + userBias(user) + itemBias(item)
  def scores(user: Int): Array[Double] = Array.tabulate(itemBias.length)(i => predict(user, i))
}

object Biases {
  def fit(idx: RatingIndex, lambda: Double, passes: Int = 10): Biases = {
    val mu = idx.mean
    val bu = new Array[Double](idx.numUsers)
    val bi = new Array[Double](idx.numItems)
    for (_ <- 0 until passes) {
      for (i <- 0 until idx.numItems) {
        val us = idx.itemUsers(i); val vs = idx.itemValues(i)
        var s = 0.0; var j = 0
        while (j < us.length) { s += vs(j) - mu - bu(us(j)); j += 1 }
        bi(i) = s / (lambda + us.length)
      }
      for (u <- 0 until idx.numUsers) {
        val is = idx.userItems(u); val vs = idx.userValues(u)
        var s = 0.0; var j = 0
        while (j < is.length) { s += vs(j) - mu - bi(is(j)); j += 1 }
        bu(u) = s / (lambda + is.length)
      }
    }
    new Biases(mu, bu, bi)
  }
}

// ---- matrix factorization ---------------------------------------------------------------

/**
 * User and item factor matrices, plus an optional bias model underneath. A movie with no
 * training ratings has no factors to speak of, so its rating prediction falls back to
 * `coldStart` (the global mean) instead of 0 stars. The bias model gets the same effect for free.
 */
final class Factors(val users: Array[Array[Double]], val items: Array[Array[Double]], val base: Option[Biases], coldStart: Double = Double.NaN) extends Recommender {
  private val cold = items.map(_.forall(_ == 0.0))
  def predict(user: Int, item: Int): Double =
    if (base.isEmpty && cold(item) && !coldStart.isNaN) coldStart
    else Linalg.dot(users(user), items(item)) + base.fold(0.0)(_.predict(user, item))
  /** Ranking ignores the cold-start fallback: an unseen movie scores 0 and sinks. */
  def scores(user: Int): Array[Double] =
    Array.tabulate(items.length)(i => Linalg.dot(users(user), items(i)) + base.fold(0.0)(_.predict(user, i)))
}

object ALS {

  /** Spark-style initialisation: Gaussian vectors scaled to unit length. */
  def init(n: Int, k: Int, rng: Random): Array[Array[Double]] = Array.fill(n) {
    val v = Array.fill(k)(rng.nextGaussian())
    val norm = math.sqrt(v.map(x => x * x).sum)
    v.map(_ / norm)
  }

  private def parallel(n: Int)(body: Int => Unit): Unit = IntStream.range(0, n).parallel().forEach(i => body(i))

  /**
   * Explicit ALS (ALS-WR, Zhou et al. 2008, the objective Spark MLlib uses):
   *   minimise Σ (r_ui − x_u·y_i)² + λ (Σ_u n_u |x_u|² + Σ_i n_i |y_i|²)
   * Each half-step is an independent ridge regression per user (or item), solved exactly.
   * With `biases`, it factorises what the bias model leaves over instead of the raw ratings.
   */
  def explicit(idx: RatingIndex, rank: Int, lambda: Double, iterations: Int = 15, seed: Long = 1, biases: Option[Biases] = None): Factors = {
    val rng = new Random(seed)
    val users = Array.fill(idx.numUsers)(new Array[Double](rank))
    val items = init(idx.numItems, rank, rng)
    def target(u: Int, i: Int, r: Double) = r - biases.fold(0.0)(_.predict(u, i))

    def halfStep(out: Array[Array[Double]], fixed: Array[Array[Double]], others: Array[Array[Int]], values: Array[Array[Double]], isUser: Boolean): Unit =
      parallel(out.length) { a =>
        val os = others(a); val vs = values(a)
        if (os.isEmpty) java.util.Arrays.fill(out(a), 0.0)
        else {
          val m = new Array[Double](rank * rank); val b = new Array[Double](rank)
          var j = 0
          while (j < os.length) {
            val y = fixed(os(j))
            val t = if (isUser) target(a, os(j), vs(j)) else target(os(j), a, vs(j))
            var p = 0
            while (p < rank) { val yp = y(p); b(p) += t * yp; var q = 0; while (q < rank) { m(p * rank + q) += yp * y(q); q += 1 }; p += 1 }
            j += 1
          }
          val reg = lambda * os.length
          var p = 0; while (p < rank) { m(p * rank + p) += reg; p += 1 }
          out(a) = Linalg.solveSpd(m, b, rank)
        }
      }

    for (_ <- 0 until iterations) {
      halfStep(users, items, idx.userItems, idx.userValues, isUser = true)
      halfStep(items, users, idx.itemUsers, idx.itemValues, isUser = false)
    }
    new Factors(users, items, biases, coldStart = idx.mean)
  }

  /**
   * Implicit ALS (Hu, Koren & Volinsky 2008) as Spark implements it. Every movie a user rated is
   * a positive (p = 1) with confidence c = 1 + α·r; every unrated movie is a weak negative with
   * confidence 1. The YᵀY trick keeps each solve proportional to the movies the user rated:
   *   x_u = (YᵀY + Σ_{i∈I_u} (c_ui − 1) y_i y_iᵀ + λ n_u I)⁻¹ Σ_{i∈I_u} c_ui y_i
   * Scores are preferences, not ratings, so this model only ranks.
   */
  def implicitPrefs(idx: RatingIndex, rank: Int, lambda: Double, alpha: Double, iterations: Int = 15, seed: Long = 1): Factors = {
    val rng = new Random(seed)
    val users = Array.fill(idx.numUsers)(new Array[Double](rank))
    val items = init(idx.numItems, rank, rng)

    def halfStep(out: Array[Array[Double]], fixed: Array[Array[Double]], others: Array[Array[Int]], values: Array[Array[Double]]): Unit = {
      val yty = Linalg.gram(fixed, rank)
      parallel(out.length) { a =>
        out(a) = if (others(a).isEmpty) new Array[Double](rank) else implicitSolve(fixed, yty, others(a), values(a), rank, lambda, alpha)
      }
    }

    for (_ <- 0 until iterations) {
      halfStep(users, items, idx.userItems, idx.userValues)
      halfStep(items, users, idx.itemUsers, idx.itemValues)
    }
    new Factors(users, items, None)
  }

  /**
   * One user's (or one item's) implicit-ALS solve, given the other side's factors `fixed`, their
   * Gram matrix `yty`, and the ids and star ratings this user rated. The browser demo does the
   * same solve in JavaScript to place a new user from a handful of ratings.
   */
  def implicitSolve(fixed: Array[Array[Double]], yty: Array[Double], ids: Array[Int], stars: Array[Double],
                    rank: Int, lambda: Double, alpha: Double): Array[Double] = {
    val m = yty.clone(); val b = new Array[Double](rank)
    var j = 0
    while (j < ids.length) {
      val y = fixed(ids(j)); val c = 1 + alpha * stars(j)
      var p = 0
      while (p < rank) { val yp = y(p); b(p) += c * yp; var q = 0; while (q < rank) { m(p * rank + q) += (c - 1) * yp * y(q); q += 1 }; p += 1 }
      j += 1
    }
    val reg = lambda * ids.length
    var p = 0; while (p < rank) { m(p * rank + p) += reg; p += 1 }
    Linalg.solveSpd(m, b, rank)
  }
}
