package com.keepit.search.result

import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.index.Analyzer
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.QueryParser
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUser
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import scala.collection.immutable.SortedMap
import scala.concurrent.Promise
import scala.concurrent.Future
import scala.concurrent.duration._
import java.io.StringReader
import play.api.libs.json._
import com.keepit.search.ArticleSearchResult
import com.keepit.search.Lang
import com.keepit.search.SearchConfigExperiment
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute

class ResultDecorator(
  userId: Id[User],
  query: String,
  lang: Lang,
  showExperts: Boolean,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait
) extends Logging {

  private[this] val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
  private[this] val terms = Highlighter.getQueryTerms(query, analyzer)

  def decorate(
    result: MergedSearchResult,
    mayHaveMoreHits: Boolean,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    idFilter: Set[Long]
  ): DecoratedResult = {
    val hits = result.hits
    val users = hits.foldLeft(Set.empty[Id[User]]){ (s, h) => s ++ h.users }
    val usersFuture = if (users.isEmpty) Future.successful(Map.empty[Id[User], BasicUser]) else shoeboxClient.getBasicUsers(users.toSeq)
    val expertsFuture = if (showExperts) { suggestExperts(hits, shoeboxClient) } else { Promise.successful(List.empty[Id[User]]).future }

    val highlightedHits = highlight(hits)

    val basicUserMap = monitoredAwait.result(usersFuture, 5 seconds, s"getting baisc users").map{ case (id, bu) => (id -> Json.toJson(bu).asInstanceOf[JsObject]) }
    val decoratedHits = addBasicUsers(highlightedHits, result.friendStats, basicUserMap)
    val expertIds = monitoredAwait.result(expertsFuture, 100 milliseconds, s"suggesting experts", List.empty[Id[User]]).filter(_.id != userId.id).take(3)
    val experts = expertIds.flatMap{ expert => basicUserMap.get(expert) }

    new DecoratedResult(
      ExternalId[ArticleSearchResult](),
      decoratedHits,
      result.myTotal,
      result.friendsTotal,
      result.othersTotal,
      query,
      userId,
      idFilter,
      mayHaveMoreHits,
      result.show,
      searchExperimentId,
      experts)
  }

  private def highlight(hits: Seq[DetailedSearchHit]): Seq[DetailedSearchHit] = {
    hits.map{ h => h.set("bookmark", highlight(h.bookmark).json) }
  }

  private def highlight(h: BasicSearchHit): BasicSearchHit = {
    val titleMatches = h.title.map(t => Highlighter.highlight(t, analyzer, "", terms))
    val urlMatches = Some(Highlighter.highlight(h.url, analyzer, "", terms))
    h.addMatches(titleMatches, urlMatches)
  }

  private def addBasicUsers(hits: Seq[DetailedSearchHit], friendStats: FriendStats, basicUserMap: Map[Id[User], JsObject]): Seq[DetailedSearchHit] = {
    hits.map{ h =>
      val basicUsers = h.users.sortBy{ id => - friendStats.score(id) }.flatMap(basicUserMap.get(_))
      h.set("basicUsers", JsArray(basicUsers))
    }
  }

  private def suggestExperts(hits: Seq[DetailedSearchHit], shoeboxClient: ShoeboxServiceClient): Future[Seq[Id[User]]] = {
    val urisAndUsers = hits.map{ hit => (hit.uriId, hit.users) }
    if (urisAndUsers.map{_._2}.flatten.distinct.size < 2){
      Promise.successful(List.empty[Id[User]]).future
    } else{
      shoeboxClient.suggestExperts(urisAndUsers)
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
      parser.parse(queryText).map{ query => QueryUtil.getTerms("", query).map(_.text) }.getOrElse(Set.empty)
    } else {
      Set.empty
    }
  }

  def highlight(text: String, analyzer: Analyzer, field: String, terms: Option[Set[String]]): Seq[(Int, Int)] = {
    terms match {
      case Some(terms) => highlight(text, analyzer, field, terms)
      case _ =>
        log.error("no term specified")
        emptyMatches
    }
  }

  def highlight(rawText: String, analyzer: Analyzer, field: String, terms: Set[String]): Seq[(Int, Int)] = {
    val text = specialCharRegex.replaceAllIn(rawText, " ")
    var positions: SortedMap[Int, Int] = SortedMap.empty[Int, Int]
    val ts = analyzer.tokenStream(field, new StringReader(text))
    if (ts.hasAttribute(classOf[OffsetAttribute]) && ts.hasAttribute(classOf[CharTermAttribute])) {
      val termAttr = ts.getAttribute(classOf[CharTermAttribute])
      val offsetAttr = ts.getAttribute(classOf[OffsetAttribute])
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
      var curStart = -1
      var curEnd = -1
      positions.foreach{ case (start, end) =>
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
      emptyMatches
    }
  }
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
  searchExperimentId: Option[Id[SearchConfigExperiment]],
  experts: Seq[JsObject]
)
