package com.keepit.search.result

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.{ SearchFilter, ArticleSearchResult, Lang, SearchConfigExperiment }
import com.keepit.search.index.Analyzer
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.QueryParser
import com.keepit.shoebox.ShoeboxServiceClient
import scala.collection.immutable.SortedMap
import scala.concurrent.Promise
import scala.concurrent.Future
import scala.concurrent.duration._
import java.io.StringReader
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute

class ResultDecorator(
    userId: Id[User],
    query: String,
    lang: Lang,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    shoeboxClient: ShoeboxServiceClient,
    monitoredAwait: MonitoredAwait) extends Logging {

  private[this] val externalId = ExternalId[ArticleSearchResult]()
  private[this] val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
  private[this] val terms = Highlighter.getQueryTerms(query, analyzer)

  def decorate(result: PartialSearchResult, searchFilter: SearchFilter, withUriSummary: Boolean): DecoratedResult = {
    val hits = result.hits
    val users = hits.foldLeft(Set.empty[Id[User]]) { (s, h) => s ++ h.users }
    val usersFuture = if (users.isEmpty) Future.successful(Map.empty[Id[User], JsObject]) else {
      shoeboxClient.getBasicUsers(users.toSeq).map { _.map { case (id, bu) => (id -> Json.toJson(bu).asInstanceOf[JsObject]) } }
    }
    val uriSummariesFuture = if (hits.isEmpty || !withUriSummary) Future.successful(Map.empty[Id[NormalizedURI], URISummary]) else {
      shoeboxClient.getUriSummaries(hits.map(_.uriId))
    }

    val highlightedHits = highlight(hits)

    val mayHaveMoreHits = {
      val total = (if (searchFilter.includeMine) result.myTotal else 0) + (if (searchFilter.includeFriends) result.friendsTotal else 0) + (if (searchFilter.includeOthers) result.othersTotal else 0)
      result.hits.size < total
    }

    val idFilter = result.hits.foldLeft(searchFilter.idFilter.toSet) { (s, h) => s + h.uriId.id }

    val (basicUserJsonMap, uriSummaryMap) = monitoredAwait.result(usersFuture zip uriSummariesFuture, 5 seconds, s"getting basic users and uri external ids")
    var decoratedHits = addBasicUsers(highlightedHits, result.friendStats, basicUserJsonMap)
    decoratedHits = addUriSummary(decoratedHits, uriSummaryMap)

    new DecoratedResult(
      externalId,
      decoratedHits,
      result.myTotal,
      result.friendsTotal,
      result.othersTotal,
      query,
      userId,
      idFilter,
      mayHaveMoreHits,
      result.show,
      result.cutPoint,
      searchExperimentId)
  }

  private def highlight(hits: Seq[DetailedSearchHit]): Seq[DetailedSearchHit] = {
    hits.map { h => h.set("bookmark", highlight(h.bookmark).json) }
  }

  private def highlight(h: BasicSearchHit): BasicSearchHit = {
    val titleMatches = h.title.map(t => Highlighter.highlight(t, analyzer, "", terms))
    val urlMatches = Some(Highlighter.highlight(h.url, analyzer, "", terms))
    h.addMatches(titleMatches, urlMatches)
  }

  private def addBasicUsers(hits: Seq[DetailedSearchHit], friendStats: FriendStats, basicUserMap: Map[Id[User], JsObject]): Seq[DetailedSearchHit] = {
    hits.map { h =>
      val basicUsers = h.users.sortBy { id => -friendStats.score(id) }.flatMap(basicUserMap.get(_))
      h.set("basicUsers", JsArray(basicUsers))
    }
  }

  private def addUriSummary(hits: Seq[DetailedSearchHit], uriSummaryMap: Map[Id[NormalizedURI], URISummary]) = {
    hits.map { h =>
      uriSummaryMap.get(h.uriId).map(uriSummary => h.set("uriSummary", Json.toJson(uriSummary))).getOrElse(h)
    }
  }
}

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

case class DecoratedResult(
  uuid: ExternalId[ArticleSearchResult],
  hits: Seq[DetailedSearchHit],
  myTotal: Int,
  friendsTotal: Int,
  othersTotal: Int,
  query: String,
  userId: Id[User],
  idFilter: Set[Long],
  mayHaveMoreHits: Boolean,
  show: Boolean,
  cutPoint: Int,
  searchExperimentId: Option[Id[SearchConfigExperiment]])
