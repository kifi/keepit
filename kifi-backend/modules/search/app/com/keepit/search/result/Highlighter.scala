package com.keepit.search.result

import java.io.StringReader
import com.keepit.common.logging.Logging
import com.keepit.search.engine.parser.{ DefaultSyntax, QueryParser }
import com.keepit.search.engine.query.QueryUtil
import com.keepit.search.index.Analyzer
import org.apache.lucene.analysis.tokenattributes.{ CharTermAttribute, OffsetAttribute }
import play.api.libs.json.{ Json, JsArray }

import scala.collection.immutable.SortedMap

object Highlighter extends Logging {

  private[this] val specialCharRegex = """[/\.:#&+~_]""".r

  private[this] val emptyMatches = Seq.empty[(Int, Int)]

  def getQueryTerms(queryText: String, analyzer: Analyzer): Set[String] = {
    if (queryText != null && queryText.trim.length != 0) {
      // use the minimum parser to avoid expansions etc.
      val parser = new QueryParser(analyzer, analyzer) with DefaultSyntax
      parser.parse(queryText).map { query => QueryUtil.getTerms("", query).map(_.text) }.getOrElse(Set.empty)
    } else {
      Set.empty
    }
  }

  def highlight(rawText: String, analyzer: Analyzer, field: String, terms: Set[String]): Seq[(Int, Int)] = {
    val text = specialCharRegex.replaceAllIn(rawText, " ")
    var positions: SortedMap[Int, Int] = SortedMap.empty[Int, Int]
    val ts = analyzer.tokenStream(field, new StringReader(text))
    if (ts.hasAttribute(classOf[OffsetAttribute]) && ts.hasAttribute(classOf[CharTermAttribute])) {
      val termAttr = ts.getAttribute(classOf[CharTermAttribute])
      val offsetAttr = ts.getAttribute(classOf[OffsetAttribute])
      try {
        ts.reset()
        while (ts.incrementToken()) {
          val termString = new String(termAttr.buffer(), 0, termAttr.length())
          if (terms.contains(termString)) {
            val thisStart = offsetAttr.startOffset()
            val thisEnd = offsetAttr.endOffset()
            positions.get(thisStart) match {
              case Some(endOffset) =>
                if (endOffset < thisEnd) positions += (thisStart -> thisEnd)
              case _ => positions += (thisStart -> thisEnd)
            }
          }
        }
        ts.end()
      } finally {
        ts.close()
      }
      var curStart = -1
      var curEnd = -1
      positions.foreach {
        case (start, end) =>
          if (start < curEnd) { // overlapping
            if (curEnd < end) {
              positions += (curStart -> end) // extend the highlight region
              positions -= start
              curEnd = end
            } else { // inclusion. remove it
              positions -= start
            }
          } else {
            curStart = start
            curEnd = end
          }
      }
      if (positions.nonEmpty) positions.toSeq else emptyMatches
    } else {
      if (ts.hasAttribute(classOf[OffsetAttribute])) log.error("offset attribute not found")
      if (ts.hasAttribute(classOf[CharTermAttribute])) log.error("char term attribute not found")
      ts.end()
      ts.close()
      emptyMatches
    }
  }

  def formatMatches(matches: Seq[(Int, Int)]): JsArray = JsArray(matches.map(h => Json.arr(h._1, (h._2 - h._1))))
}