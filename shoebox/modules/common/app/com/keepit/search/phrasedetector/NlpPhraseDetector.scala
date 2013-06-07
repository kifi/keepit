package com.keepit.search.phrasedetector

import org.apache.lucene.analysis.Analyzer
import com.keepit.search.nlp.NlpParser
import com.keepit.search.LangDetector
import com.keepit.search.query.QueryUtil
import scala.collection.mutable.{ListBuffer, Map => MutMap}


object NlpPhraseDetector {
  val LOWER_LIMIT = 10
  val UPPER_LIMIT = 100

  // analyzer should be consistent with the one in mainSearcher
  def detectAll(queryText: String, analyzer: Analyzer) = {
    if (!toDetect(queryText)) Set.empty[(Int, Int)]
    else {
      val cons = NlpParser.getNonOverlappingConstituents(queryText)     // token offsets
      val termPos = QueryUtil.getTermOffsets(analyzer, queryText).map{x => (x._1, x._2 - 1)}       // char offsets
      val offsets = toCharOffsets(cons, queryText).sortWith((a, b) => (a._1 < b._1))
      var p = 0
      var i = 0
      val phrases = ListBuffer.empty[(Int, Int)]      // (start, len)
      while(i < termPos.length){
        val q = offsets.indexWhere(interval => termPos(i)._2 <= interval._2 && termPos(i)._1 >= interval._1 , p)
        if (q == -1){
          i += 1
        } else {
          p = q + 1
          val start = i
          var phraseLen = 1
          i += 1
          while( i < termPos.length &&  offsets(q)._1 <= termPos(i)._1 && termPos(i)._2 <= offsets(q)._2){
            phraseLen += 1
            i += 1
          }
          if (phraseLen > 1) phrases.append((start, phraseLen))
        }
      }
      phrases.toSet
    }
  }

  private def toDetect(queryText: String): Boolean = {
    if (queryText.length() > UPPER_LIMIT || queryText.length() < LOWER_LIMIT) return false
    if (queryText.toCharArray.exists(x => !(x.isLetterOrDigit || x.isSpaceChar))) return false
    if (LangDetector.detectShortText(queryText).lang != "en") return false
    return true
  }

  def toCharOffsets(tokenOffsets: Seq[(Int, Int)], queryText: String) = {
    val chars = queryText.toCharArray
    var p = 0
    var cnt = 0
    val mapper = MutMap.empty[Int, (Int, Int)]
    while(p < chars.length){
      val start = chars.indexWhere(! _.isSpaceChar, p)
      if (start != -1){
        val end = chars.indexWhere(_.isSpaceChar, start + 1) match {
          case -1 => chars.length - 1
          case i : Int => i - 1
        }
        mapper += (cnt -> (start, end))
        cnt += 1
        p = end + 1
      } else p = chars.length
    }
    tokenOffsets.map{ case (i, j) =>
       val (start, end) = (mapper.get(i), mapper.get(j))
       if ( start != None && end != None ) Some((start.get._1, end.get._2)) else None
    }.flatMap(x => x)
  }
}