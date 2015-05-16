package com.keepit.search.result

import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.db.Id
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.store.S3ImageConfig
import com.keepit.model._
import com.keepit.rover.RoverServiceClient
import com.keepit.rover.model.RoverUriSummary
import com.keepit.search.{ SearchFilter, ArticleSearchResult, Lang, SearchConfigExperiment }
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Future
import scala.concurrent.duration._
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ResultDecorator(
    userId: Id[User],
    query: String,
    lang: Lang,
    searchExperimentId: Option[Id[SearchConfigExperiment]],
    shoeboxClient: ShoeboxServiceClient,
    rover: RoverServiceClient,
    monitoredAwait: MonitoredAwait,
    implicit val imageConfig: S3ImageConfig) extends Logging {

  private[this] val externalId = ExternalId[ArticleSearchResult]()
  private[this] val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(lang)
  private[this] val terms = Highlighter.getQueryTerms(query, analyzer)

  def decorate(result: PartialSearchResult, searchFilter: SearchFilter, withUriSummary: Boolean): DecoratedResult = {
    val hits = result.hits
    val users = hits.foldLeft(Set.empty[Id[User]]) { (s, h) => s ++ h.users }
    val usersFuture = if (users.isEmpty) Future.successful(Map.empty[Id[User], JsObject]) else {
      shoeboxClient.getBasicUsers(users.toSeq).map { _.map { case (id, bu) => (id -> Json.toJson(bu).asInstanceOf[JsObject]) } }
    }
    val uriSummariesFuture = if (hits.isEmpty || !withUriSummary) Future.successful(Map.empty[Id[NormalizedURI], RoverUriSummary]) else {
      rover.getUriSummaryByUris(hits.map(_.uriId).toSet)
    }

    val highlightedHits = highlight(hits)

    val mayHaveMoreHits = {
      val total = (if (searchFilter.includeMine) result.myTotal else 0) + (if (searchFilter.includeFriends) result.friendsTotal else 0) + (if (searchFilter.includeOthers) result.othersTotal else 0)
      result.hits.size < total
    }

    val idFilter = result.hits.foldLeft(searchFilter.idFilter.toSet) { (s, h) => s + h.uriId.id }

    val (basicUserJsonMap, uriSummaryMap) = monitoredAwait.result(usersFuture zip uriSummariesFuture, 5 seconds, s"getting basic users and uri external ids")
    var decoratedHits = addBasicUsers(highlightedHits, result.friendStats, basicUserJsonMap)
    decoratedHits = addUriSummary(decoratedHits, uriSummaryMap.mapValues(_.toUriSummary(ProcessedImageSize.Medium.idealSize)))

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
  searchExperimentId: Option[Id[SearchConfigExperiment]])
