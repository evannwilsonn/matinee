package matinee

import scala.io.Source
import scala.util.Random

/** One rating, with users and movies already mapped to dense 0-based indices. */
final case class Rating(user: Int, item: Int, value: Double)

final case class Movie(id: Int, title: String, year: Option[Int], genres: Seq[String])

/**
 * The MovieLens ratings with dense ids. `userIds(u)` and `movieIds(i)` map back to the
 * original MovieLens ids; `movies` has the title and genres for each dense item index.
 */
final case class Dataset(ratings: IndexedSeq[Rating], userIds: IndexedSeq[Int], movieIds: IndexedSeq[Int], movies: IndexedSeq[Movie]) {
  def numUsers: Int = userIds.length
  def numItems: Int = movieIds.length
}

object Data {

  /** Splits one CSV line, honouring double quotes (movie titles contain commas). */
  def splitCsv(line: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val ch = line.charAt(i)
      if (inQuotes) {
        if (ch == '"' && i + 1 < line.length && line.charAt(i + 1) == '"') { cur += '"'; i += 1 }
        else if (ch == '"') inQuotes = false
        else cur += ch
      } else if (ch == '"') inQuotes = true
      else if (ch == ',') { out += cur.toString; cur.clear() }
      else cur += ch
      i += 1
    }
    out += cur.toString
    out.result()
  }

  private val YearAtEnd = """^(.*?)\s*\((\d{4})\)\s*$""".r

  /** "Heat (1995)" becomes ("Heat", Some(1995)); a few titles have no year. */
  def parseTitle(raw: String): (String, Option[Int]) = raw.trim match {
    case YearAtEnd(t, y) => (t, Some(y.toInt))
    case t               => (t, None)
  }

  def load(dir: String): Dataset = {
    def lines(name: String): List[String] = {
      val src = Source.fromFile(s"$dir/$name", "UTF-8")
      try src.getLines().drop(1).filter(_.nonEmpty).toList finally src.close()
    }
    val movieInfo = lines("movies.csv").map { l =>
      val f = splitCsv(l)
      val (title, year) = parseTitle(f(1))
      val genres = if (f(2) == "(no genres listed)") Nil else f(2).split('|').toList
      f(0).toInt -> Movie(f(0).toInt, title, year, genres)
    }.toMap

    val raw = lines("ratings.csv").map { l =>
      val f = l.split(',')
      (f(0).toInt, f(1).toInt, f(2).toDouble)
    }
    val userIds = raw.map(_._1).distinct.sorted.toVector
    val movieIds = raw.map(_._2).distinct.sorted.toVector
    val uIdx = userIds.zipWithIndex.toMap
    val iIdx = movieIds.zipWithIndex.toMap
    val ratings = raw.map { case (u, m, r) => Rating(uIdx(u), iIdx(m), r) }.toVector
    val movies = movieIds.map(id => movieInfo.getOrElse(id, Movie(id, s"Movie $id", None, Nil)))
    Dataset(ratings, userIds, movieIds, movies)
  }

  /**
   * Per-user holdout: each user keeps (1 - share) of their ratings for training and the rest
   * are held out. Splitting within each user means every test user has history to learn from,
   * which is the situation the batch job is in.
   */
  def splitPerUser(ratings: IndexedSeq[Rating], share: Double, seed: Long): (Vector[Rating], Vector[Rating]) = {
    val rng = new Random(seed)
    val train = Vector.newBuilder[Rating]
    val test = Vector.newBuilder[Rating]
    for ((_, rs) <- ratings.groupBy(_.user).toSeq.sortBy(_._1)) {
      val shuffled = rng.shuffle(rs.sortBy(_.item))
      val nTest = math.round(rs.length * share).toInt
      test ++= shuffled.take(nTest)
      train ++= shuffled.drop(nTest)
    }
    (train.result(), test.result())
  }
}
