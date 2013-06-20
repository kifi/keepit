package com.keepit.search

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.{User, NormalizedURI}
import org.joda.time.DateTime
import com.keepit.common.time._

case class ArticleHit(uriId: Id[NormalizedURI], score: Float, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[Id[User]], bookmarkCount: Int)

case class ArticleSearchResult(
  last: Option[ExternalId[ArticleSearchResultRef]], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
  query: String,
  hits: Seq[ArticleHit],
  myTotal: Int,
  friendsTotal: Int,
  mayHaveMoreHits: Boolean,
  scorings: Seq[Scoring],
  filter: Set[Long],
  millisPassed: Int,
  pageNumber: Int,
  uuid: ExternalId[ArticleSearchResultRef] = ExternalId(),
  time: DateTime = currentDateTime,
  svVariance: Float = -1.0f,			// semantic vector variance
  svExistenceVar: Float = -1.0f,
  toShow: Boolean = true,
  timeLogs: Option[SearchTimeLogs] = None)


class Scoring(val textScore: Float, val normalizedTextScore: Float, val bookmarkScore: Float, val recencyScore: Float) extends Equals {
  var boostedTextScore: Float = Float.NaN
  var boostedBookmarkScore: Float = Float.NaN
  var boostedRecencyScore: Float = Float.NaN

  def score(textBoost: Float, bookmarkBoost: Float, recencyBoost: Float) = {
    boostedTextScore = normalizedTextScore * textBoost
    boostedBookmarkScore = bookmarkScore * bookmarkBoost
    boostedRecencyScore = recencyScore * recencyBoost

    boostedTextScore + boostedBookmarkScore + boostedRecencyScore
  }

  override def toString() = {
    "Scoring(%f, %f, %f, %f, %f, %f, %f)".format(textScore, normalizedTextScore, bookmarkScore, recencyScore, boostedTextScore, boostedBookmarkScore, boostedRecencyScore)
  }

  def canEqual(other: Any) = {
    other.isInstanceOf[com.keepit.search.Scoring]
  }

  override def equals(other: Any) = {
    other match {
      case that: com.keepit.search.Scoring => that.canEqual(Scoring.this) && textScore == that.textScore && normalizedTextScore == that.normalizedTextScore && bookmarkScore == that.bookmarkScore
      case _ => false
    }
  }

  override def hashCode() = {
    val prime = 41
    prime * (prime * (prime + textScore.hashCode) + normalizedTextScore.hashCode) + bookmarkScore.hashCode
  }
}

case class SearchTimeLogs(
  socialGraphInfo: Long = 0,
  getClickBoost: Long = 0,
  queryParsing: Long = 0,
  personalizedSearcher: Long = 0,
  search: Long = 0,
  processHits: Long = 0,
  total: Long = 0) {
  override def toString() = {
    s"search time summary: total = $total, approx sum of: socialGraphInfo = $socialGraphInfo, getClickBoost = $getClickBoost, queryParsing = $queryParsing, " +
      s"personalizedSearcher = $personalizedSearcher, search = $search, processHits = $processHits"
  }
}
