package com.keepit.search.phrasedetector

import org.apache.lucene.analysis.Analyzer
import com.keepit.search.nlp.NlpParser
import com.keepit.search.Lang
import com.keepit.search.query.QueryUtil
import scala.collection.mutable.ArrayBuffer

object NlpPhraseDetector {
  val LOWER_LIMIT = 10
  val UPPER_LIMIT = 100

  // analyzer should be consistent with the one in mainSearcher
  def detectAll(queryText: String, analyzer: Analyzer, lang: Lang) = {
    if (!toDetect(queryText, lang)) Set.empty[(Int, Int)]
    else {
      val cons = NlpParser.getNonOverlappingConstituents(queryText) // token offsets
      val termPos = QueryUtil.getTermOffsets(analyzer, queryText) // char offsets (start, end), end is exclusive
      val offsets = toCharOffsets(cons, queryText).sortWith((a, b) => (a._1 < b._1)) // (start, end), end is exclusive
      var p = 0
      var i = 0
      var phrases = Set.empty[(Int, Int)] // (start, len)
      while (i < termPos.length) {
        val q = offsets.indexWhere(contains(_, termPos(i)), p)
        if (q == -1) {
          i += 1
        } else {
          p = q + 1
          val start = i
          i += 1
          while (i < termPos.length && contains(offsets(q), termPos(i))) { i += 1 }
          if (i - start > 1) phrases += ((start, i - start))
        }
      }
      phrases
    }
  }

  @inline private[this] def contains(interval: (Int, Int), other: (Int, Int)): Boolean = {
    (interval._1 <= other._1 && other._2 <= interval._2)
  }

  private def toDetect(queryText: String, lang: Lang): Boolean = {
    if (lang.lang != "en") return false
    if (queryText.length() > UPPER_LIMIT || queryText.length() < LOWER_LIMIT) return false
    if (queryText.split(" ").filter(!_.isEmpty).size <= 1) return false
    if (queryText.exists(x => !(x.isLetterOrDigit || x.isSpaceChar))) return false
    return true
  }

  // e.g. queryText = "ab cd ef", 3 tokens. token indexes (0,1) => char indexes (0, 5), end is exclusive
  def toCharOffsets(tokenOffsets: Seq[(Int, Int)], queryText: String) = {
    val length = queryText.length
    var p = 0
    val mapper = new ArrayBuffer[(Int, Int)]
    while (p < length) {
      val start = queryText.indexWhere(!_.isSpaceChar, p)
      if (start != -1) {
        val end = queryText.indexWhere(_.isSpaceChar, start + 1)
        p = if (end == -1) length else end
        mapper += ((start, p))
      } else {
        p = length
      }
    }
    val cnt = mapper.size
    tokenOffsets.flatMap { case (i, j) => if (i < cnt && j < cnt) Some((mapper(i)._1, mapper(j)._2)) else None }
  }
}
