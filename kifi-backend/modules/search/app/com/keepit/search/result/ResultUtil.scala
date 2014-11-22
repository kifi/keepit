package com.keepit.search.result

import com.keepit.search.engine.Visibility
import com.keepit.search.engine.result.{ KifiShardHit, KifiPlainResult }
import play.api.libs.json._
import com.keepit.common.db.{ Id, ExternalId }
import org.joda.time.DateTime
import com.keepit.search.ArticleHit
import com.keepit.search.ArticleSearchResult
import com.keepit.model.{ NormalizedURI, URISummary }

object ResultUtil {

  private def jsonToKifiSearchHit(json: JsObject): KifiSearchHit = {
    val uriSummaryJson = (json \ "uriSummary")
    // All the fields of URISummary are nullable so we want to distinguish between "null" and an empty URI summary
    val uriSummary = if (!uriSummaryJson.isInstanceOf[JsUndefined]) {
      uriSummaryJson.asOpt[URISummary].map("uriSummary" -> Json.toJson(_)).toList
    } else List()
    KifiSearchHit(JsObject(List(
      "count" -> (json \ "bookmarkCount"),
      "bookmark" -> (json \ "bookmark"),
      "users" -> (json \ "basicUsers"),
      "score" -> (json \ "score"),
      "isMyBookmark" -> (json \ "isMyBookmark"),
      "isPrivate" -> (json \ "isPrivate")
    ) ++ uriSummary))
  }

  def toKifiSearchHits(hits: Seq[DetailedSearchHit], sanitize: Boolean): Seq[KifiSearchHit] = {
    if (sanitize) hits.map { h => jsonToKifiSearchHit(h.sanitized.json) }
    else hits.map { h => jsonToKifiSearchHit(h.json) }
  }

  def toArticleSearchResult(
    res: DecoratedResult,
    last: Option[String], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
    mergedResult: PartialSearchResult,
    millisPassed: Int,
    pageNumber: Int,
    previousHits: Int,
    time: DateTime,
    lang: String): ArticleSearchResult = {

    def toArticleHit(hit: DetailedSearchHit): ArticleHit = {
      ArticleHit(
        hit.uriId,
        hit.score,
        hit.textScore,
        hit.isMyBookmark,
        hit.users.size > 0)
    }

    val lastUUID = for { str <- last if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)

    ArticleSearchResult(
      lastUUID,
      res.query,
      mergedResult.hits map toArticleHit,
      mergedResult.myTotal,
      mergedResult.friendsTotal,
      mergedResult.othersTotal,
      res.mayHaveMoreHits,
      res.idFilter,
      millisPassed,
      pageNumber,
      previousHits,
      res.uuid,
      time,
      mergedResult.show,
      lang
    )
  }

  def toArticleSearchResult(
    res: KifiPlainResult,
    last: Option[String], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
    millisPassed: Int,
    pageNumber: Int,
    previousHits: Int,
    time: DateTime,
    lang: String): ArticleSearchResult = {

    def toArticleHit(hit: KifiShardHit): ArticleHit = {
      ArticleHit(
        Id[NormalizedURI](hit.id),
        hit.finalScore,
        hit.score,
        (hit.visibility & (Visibility.OWNER | Visibility.MEMBER)) != 0,
        (hit.visibility & Visibility.NETWORK) != 0)
    }

    val lastUUID = for { str <- last if str.nonEmpty } yield ExternalId[ArticleSearchResult](str)

    ArticleSearchResult(
      lastUUID,
      res.query,
      res.hits map toArticleHit,
      res.myTotal,
      res.friendsTotal,
      res.othersTotal,
      res.mayHaveMoreHits,
      res.idFilter,
      millisPassed,
      pageNumber,
      previousHits,
      res.uuid,
      time,
      res.show,
      lang
    )
  }
}
