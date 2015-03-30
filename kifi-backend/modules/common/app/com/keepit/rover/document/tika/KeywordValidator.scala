package com.keepit.rover.document.tika

import com.keepit.search.util.AhoCorasick

object KeywordValidator {
  val specialRegex = """[,;:/]\s*""".r
  val spaceRegex = """\s+""".r
}

class KeywordValidator(keywordCandidates: Seq[String]) {

  private[this] val matcher = new AhoCorasick[Char, (String, Int)](keywordCandidates.zipWithIndex.map { case (k, i) => (s" ${k.toLowerCase} ".toCharArray().toSeq, (k, i)) })

  private[this] var validatedKeywords = Set.empty[(String, Int)]
  private[this] val onMatch: (Int, (String, Int)) => Unit = { (pos: Int, k: (String, Int)) => validatedKeywords += k }

  private[this] var state = matcher.initialState
  private[this] var lastChar = ' '

  def startDocument(): Unit = break()

  def endDocument(): Unit = break()

  def break(): Unit = {
    state = matcher.next(' ', state)
    state.check(0, onMatch) // dummy position
  }

  def characters(ch: Array[Char]): Unit = characters(ch, 0, ch.length)

  def characters(ch: Array[Char], start: Int, length: Int): Unit = {
    var i = start
    val end = start + length
    while (i < end) {
      var char = ch(i).toLower
      if (!char.isLetterOrDigit) char = ' '
      if (char != ' ' || lastChar != ' ') {
        lastChar = char
        state = matcher.next(char, state)
        if (char == ' ') state.check(0, onMatch) // dummy position
      }
      i += 1
    }
  }

  def keywords: Seq[String] = validatedKeywords.toSeq.sortBy(_._2).map(_._1)

  def coverage: Double = validatedKeywords.size.toDouble / matcher.size.toDouble
}
