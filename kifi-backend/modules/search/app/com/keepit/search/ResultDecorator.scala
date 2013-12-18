package com.keepit.search

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
import org.apache.lucene.index.Term
import scala.collection.immutable.SortedMap
import scala.concurrent.Promise
import scala.concurrent.{Future, promise}
import scala.concurrent.duration._
import java.io.StringReader
import play.api.libs.json._

trait ResultDecorator {
  def decorate(
    hits: Seq[DetailedSearchHit],
    idFilter: Set[Long],
    mayHaveMoreHits: Boolean,
    show: Boolean,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    showExperts: Boolean
  ): DecoratedResult
}

object ResultDecorator extends Logging {

  private[this] val emptyMatches = Seq.empty[(Int, Int)]

  def apply(
    userId: Id[User],
    query: String,
    lang: Lang,
    friendStats: FriendStats,
    shoeboxClient: ShoeboxServiceClient,
    searchConfig: SearchConfig,
    monitoredAwait: MonitoredAwait
  ): ResultDecorator = {
    new ResultDecoratorImpl(userId, query, lang, friendStats, shoeboxClient, monitoredAwait)
  }

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

  def highlight(text: String, analyzer: Analyzer, field: String, terms: Set[String]): Seq[(Int, Int)] = {
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

  def highlightURL(url: String, analyzer: Analyzer, field: String, terms: Option[Set[String]]): Seq[(Int, Int)] = {
    terms match {
      case Some(terms) => highlightURL(url, analyzer, field, terms)
      case _ =>
        log.error("no term specified")
        emptyMatches
    }
  }

  private[this] val urlSpecialCharRegex = """[/\.:#&+~]""".r

  def highlightURL(url: String, analyzer: Analyzer, field: String, terms: Set[String]): Seq[(Int, Int)] = {
    val text = urlSpecialCharRegex.replaceAllIn(url, " ")
    highlight(text, analyzer, field, terms)
  }
}

class ResultDecoratorImpl(
  userId: Id[User],
  query: String,
  lang: Lang,
  friendStats: FriendStats,
  shoeboxClient: ShoeboxServiceClient,
  monitoredAwait: MonitoredAwait
) extends ResultDecorator with Logging {

  val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
  val terms = ResultDecorator.getQueryTerms(query, analyzer)

  override def decorate(
    hits: Seq[DetailedSearchHit],
    idFilter: Set[Long],
    mayHaveMoreHits: Boolean,
    show: Boolean,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    showExperts: Boolean
  ): DecoratedResult = {
    val users = hits.foldLeft(Set.empty[Id[User]]){ (s, h) => s ++ h.users }.toSeq
    val usersFuture = if (users.isEmpty) Future.successful(Map.empty[Id[User], BasicUser]) else shoeboxClient.getBasicUsers(users)
    val expertsFuture = if (showExperts) { suggestExperts(hits) } else { Promise.successful(List.empty[Id[User]]).future }

    val highlightedHits = highlight(hits)
    val basicUserMap = monitoredAwait.result(usersFuture, 5 seconds, s"getting baisc users")

    val experts = monitoredAwait.result(expertsFuture, 100 milliseconds, s"suggesting experts", List.empty[Id[User]]).filter(_.id != userId.id).take(3)
    val expertNames = experts.flatMap{ expert => basicUserMap.get(expert).map{x => x.firstName + " " + x.lastName} }
    if (expertNames.nonEmpty) log.info("experts recommended: " + expertNames.mkString(" ; "))

    new DecoratedResult(
      ExternalId[ArticleSearchResult](),
      addBasicUsers(highlightedHits, friendStats, basicUserMap),
      query,
      userId,
      idFilter,
      mayHaveMoreHits,
      show,
      searchExperimentId,
      expertNames,
      basicUserMap
    )
  }

  private def highlight(hits: Seq[DetailedSearchHit]): Seq[DetailedSearchHit] = {
    hits.map{ h => h.add("bookmark", highlight(h.bookmark).json) }
  }

  private def highlight(h: BasicSearchHit): BasicSearchHit = {
    val titleMatches = h.title.map(t => ResultDecorator.highlight(t, analyzer, "", terms))
    val urlMatches = Some(ResultDecorator.highlightURL(h.url, analyzer, "", terms))
    h.addMatches(titleMatches, urlMatches)
  }

  private def addBasicUsers(hits: Seq[DetailedSearchHit], friendStats: FriendStats, basicUserMap: Map[Id[User], BasicUser]): Seq[DetailedSearchHit] = {
    hits.map{ h =>
      val sortedUsers = h.users.sortBy{ id => - friendStats.score(id) }

      val basicUsers = h.users.flatMap(basicUserMap.get(_))
      h.add("basicUsers", JsArray(basicUsers.map{ bu => Json.toJson(bu) }))
    }
  }

  private def suggestExperts(hits: Seq[DetailedSearchHit]): Future[Seq[Id[User]]] = {
    val urisAndUsers = hits.map{ hit => (hit.uriId, hit.users) }
    if (urisAndUsers.map{_._2}.flatten.distinct.size < 2){
      Promise.successful(List.empty[Id[User]]).future
    } else{
      shoeboxClient.suggestExperts(urisAndUsers)
    }
  }

}

case class DecoratedResult(
  uuid: ExternalId[ArticleSearchResult],
  hits: Seq[DetailedSearchHit],
  query: String,
  userId: Id[User],
  idFilter: Set[Long],
  mayHaveMoreHits: Boolean,
  show: Boolean,
  searchExperimentId: Option[Id[SearchConfigExperiment]],
  expertNames: Seq[String],
  users: Map[Id[User], BasicUser]
)
