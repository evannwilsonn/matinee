package matinee

final case class RankScores(precision: Double, recall: Double, ndcg: Double, coverage: Double, users: Int)

object Metrics {

  /** Root mean squared error, with predictions clipped to the 0.5–5 star range. */
  def rmse(model: Recommender, ratings: Seq[Rating]): Double = {
    val se = ratings.map { r =>
      val p = math.min(5.0, math.max(0.5, model.predict(r.user, r.item)))
      (p - r.value) * (p - r.value)
    }
    math.sqrt(se.sum / ratings.size)
  }

  /** Indices of the k largest scores, best first. */
  def topK(scores: Array[Double], k: Int): Array[Int] = {
    val heap = new java.util.PriorityQueue[Int](k + 1, (a: Int, b: Int) => java.lang.Double.compare(scores(a), scores(b)))
    var i = 0
    while (i < scores.length) {
      if (!scores(i).isNegInfinity) {
        if (heap.size < k) heap.add(i)
        else if (scores(i) > scores(heap.peek())) { heap.poll(); heap.add(i) }
      }
      i += 1
    }
    val out = new Array[Int](heap.size)
    var j = out.length - 1
    while (!heap.isEmpty) { out(j) = heap.poll(); j -= 1 }
    out
  }

  /**
   * Top-k quality on held-out ratings. A held-out movie counts as relevant if the user rated it
   * `threshold` or higher. Candidates are every movie the user hasn't rated in training, so the
   * model has to find the good ones among ~9,700, not re-rank a short list.
   *
   * - precision@k: share of the k picks that are relevant
   * - recall@k: share of the user's relevant movies that made the k picks
   * - NDCG@k: like recall, but a hit at #1 counts more than a hit at #10
   * - coverage: share of the catalogue that appears in anyone's top k
   *
   * Users with no relevant held-out movie can't be scored and are skipped.
   */
  def ranking(model: Recommender, train: RatingIndex, heldOut: Seq[Rating], k: Int = 10, threshold: Double = 4.0): RankScores = {
    val relevant = heldOut.filter(_.value >= threshold).groupBy(_.user).view.mapValues(_.map(_.item).toSet).toMap
    val shown = scala.collection.mutable.Set.empty[Int]
    var p = 0.0; var r = 0.0; var n = 0.0
    val users = relevant.keys.toSeq.sorted
    for (u <- users) {
      val scores = model.scores(u)
      train.userItems(u).foreach(i => scores(i) = Double.NegativeInfinity)
      val picks = topK(scores, k)
      shown ++= picks
      val rel = relevant(u)
      val hits = picks.map(rel.contains)
      val nHits = hits.count(identity).toDouble
      p += nHits / k
      r += nHits / rel.size
      val dcg = hits.zipWithIndex.collect { case (true, j) => 1 / log2(j + 2) }.sum
      val idcg = (0 until math.min(rel.size, k)).map(j => 1 / log2(j + 2)).sum
      n += dcg / idcg
    }
    val m = users.size.toDouble
    RankScores(p / m, r / m, n / m, shown.size.toDouble / train.numItems, users.size)
  }

  private def log2(x: Double) = math.log(x) / math.log(2)
}
