package com.keepit.search.result

import play.api.libs.json._
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.search.ArticleHit
import com.keepit.search.ArticleSearchResult
import com.keepit.search.Lang
import com.keepit.search.Scoring
import com.keepit.search.SearchConfig

object ResultUtil {

  private def jsonToKifiSearchHit(json: JsObject): KifiSearchHit = {
    val externalUriId = (json \ "externalUriId").asOpt[JsString].map("externalUriId" -> _).toList
    KifiSearchHit(JsObject(List(
      "count" -> (json \ "bookmarkCount"),
      "bookmark" -> (json \ "bookmark"),
      "users" -> (json \ "basicUsers"),
      "score" -> (json \ "score"),
      "isMyBookmark" -> (json \ "isMyBookmark"),
      "isPrivate" -> (json \ "isPrivate")
    ) ++ externalUriId))
  }

  def toKifiSearchHits(hits: Seq[DetailedSearchHit]): Seq[KifiSearchHit] = {
    hits.map{ h =>
      jsonToKifiSearchHit(h.json)
    }
  }

  def toSanitizedKifiSearchHits(hits: Seq[DetailedSearchHit]): Seq[KifiSearchHit] = {
    hits.map{ h =>
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
    lang: String
  ): ArticleSearchResult = {
    ArticleSearchResult(
      last,
      res.query,
      mergedResult.hits.map{ h => h.json.as[ArticleHit] },
      mergedResult.myTotal,
      mergedResult.friendsTotal,
      mergedResult.othersTotal,
      res.mayHaveMoreHits,
      mergedResult.hits.map{ h => (h.json \ "scoring").as[Scoring] },
      res.idFilter,
      millisPassed,
      pageNumber,
      previousHits,
      res.uuid,
      time,
      mergedResult.svVariance,
      -1.0f,
      mergedResult.show,
      Set.empty[Long],
      lang
    )
  }
}
