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

  def toKifiSearchHits(hits: Seq[DetailedSearchHit]): Seq[KifiSearchHit] = {
    hits.map{ h =>
      val json = h.json
      KifiSearchHit(JsObject(List(
        "count" -> (json \ "bookmarkCount"),
        "bookmark" -> (json \ "bookmark"),
        "users" -> (json \ "basicUsers"),
        "score" -> (json \ "score"),
        "isMyBookmark" -> (json \ "isMyBookmark"),
        "isPrivate" -> (json \ "isPrivate")
      )))
    }
  }

  def toArticleSearchResult(
    uuid: ExternalId[ArticleSearchResult],
    last: Option[ExternalId[ArticleSearchResult]], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
    query: String,
    mergedResult: MergedSearchResult,
    mayHaveMoreHits: Boolean,
    filter: Set[Long],
    millisPassed: Int,
    pageNumber: Int,
    previousHits: Int,
    time: DateTime,
    lang: Lang
  ): ArticleSearchResult = {
    ArticleSearchResult(
      last,
      query,
      mergedResult.hits.map{ h => h.json.as[ArticleHit] },
      mergedResult.myTotal,
      mergedResult.friendsTotal,
      mergedResult.othersTotal,
      mayHaveMoreHits,
      mergedResult.hits.map{ h => (h.json \ "scoring").as[Scoring] },
      filter,
      millisPassed,
      pageNumber,
      previousHits,
      uuid,
      time,
      mergedResult.svVariance,
      -1.0f,
      mergedResult.show,
      Set.empty[Long],
      lang
    )
  }
}

case class MergedSearchResult(
  hits: Seq[DetailedSearchHit],
  myTotal: Int,
  friendsTotal: Int,
  othersTotal: Int,
  friendStats: FriendStats,
  show: Boolean,
  svVariance: Float
)
