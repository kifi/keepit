package com.keepit.search

import play.api.libs.json._
import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import org.joda.time.DateTime

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
    myTotal: Int,
    friendsTotal: Int,
    othersTotal: Int,
    mayHaveMoreHits: Boolean,
    filter: Set[Long],
    millisPassed: Int,
    pageNumber: Int,
    previousHits: Int,
    time: DateTime,
    svVariance: Float,
    toShow: Boolean,
    lang: Lang,
    hits: Seq[DetailedSearchHit]
  ): ArticleSearchResult = {
    ArticleSearchResult(
      last,
      query,
      hits.map{ h => h.json.as[ArticleHit] },
      myTotal,
      friendsTotal,
      othersTotal,
      mayHaveMoreHits,
      hits.map{ h => (h.json \ "scoring").as[Scoring] },
      filter,
      millisPassed,
      pageNumber,
      previousHits,
      uuid,
      time,
      svVariance,
      -1.0f,
      toShow,
      Set.empty[Long],
      lang
    )
  }
}