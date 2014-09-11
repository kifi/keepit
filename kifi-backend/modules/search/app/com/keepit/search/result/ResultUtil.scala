package com.keepit.search.result

import play.api.libs.json._
import com.keepit.common.db.ExternalId
import org.joda.time.DateTime
import com.keepit.search.ArticleHit
import com.keepit.search.ArticleSearchResult
import com.keepit.search.Scoring
import com.keepit.model.URISummary

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

  def toKifiSearchHits(hits: Seq[DetailedSearchHit]): Seq[KifiSearchHit] = {
    hits.map { h =>
      jsonToKifiSearchHit(h.json)
    }
  }

  def toSanitizedKifiSearchHits(hits: Seq[DetailedSearchHit]): Seq[KifiSearchHit] = {
    hits.map { h =>
      jsonToKifiSearchHit(h.sanitized.json)
    }
  }

  def toArticleSearchResult(
    res: DecoratedResult,
    last: Option[ExternalId[ArticleSearchResult]], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
    mergedResult: PartialSearchResult,
    millisPassed: Int,
    pageNumber: Int,
    previousHits: Int,
    time: DateTime,
    lang: String): ArticleSearchResult = {
    ArticleSearchResult(
      last,
      res.query,
      mergedResult.hits.map { h => h.json.as[ArticleHit] },
      mergedResult.myTotal,
      mergedResult.friendsTotal,
      mergedResult.othersTotal,
      res.mayHaveMoreHits,
      Seq[Scoring](),
      res.idFilter,
      millisPassed,
      pageNumber,
      previousHits,
      res.uuid,
      time,
      -1.0f,
      -1.0f,
      mergedResult.show,
      Set.empty[Long],
      lang
    )
  }
}
