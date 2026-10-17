package matinee

/** Just enough JSON writing for results files and the demo's model export. */
object Json {
  def str(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case c if c < ' ' => sb ++= f"\\u${c.toInt}%04x"
      case c    => sb += c
    }
    (sb += '"').toString
  }

  def num(d: Double, digits: Int = 4): String =
    if (d.isNaN || d.isInfinite) "null"
    else BigDecimal(d).setScale(digits, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  def value(v: Any): String = v match {
    case null           => "null"
    case s: String      => str(s)
    case d: Double      => num(d)
    case f: Float       => num(f.toDouble)
    case i: Int         => i.toString
    case l: Long        => l.toString
    case b: Boolean     => b.toString
    case Some(x)        => value(x)
    case None           => "null"
    case m: Map[_, _]   => m.map { case (k, x) => s"${str(k.toString)}:${value(x)}" }.mkString("{", ",", "}")
    case s: Iterable[_] => s.map(value).mkString("[", ",", "]")
    case a: Array[_]    => a.map(value).mkString("[", ",", "]")
    case other          => str(other.toString)
  }

  /** One object per line in a list, so results files diff cleanly. */
  def lines(rows: Seq[Map[String, Any]]): String = rows.map(value).mkString("[\n  ", ",\n  ", "\n]\n")

  def write(path: String, text: String): Unit = {
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f, "UTF-8")
    try w.write(text) finally w.close()
  }
}
