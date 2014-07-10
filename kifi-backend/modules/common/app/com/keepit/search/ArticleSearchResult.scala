package com.keepit.search

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.{ User, NormalizedURI }
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.serializer.TraversableFormat
import com.keepit.common.logging.Logging
import scala.math._

case class ArticleHit(uriId: Id[NormalizedURI], score: Float, isMyBookmark: Boolean, isPrivate: Boolean, users: Seq[Id[User]], bookmarkCount: Int)

object ArticleHit {
  implicit val format = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'score).format[Float] and
    (__ \ 'isMyBookmark).format[Boolean] and
    (__ \ 'isPrivate).format[Boolean] and
    (__ \ 'users).format(TraversableFormat.seq(Id.format[User])) and
    (__ \ 'bookmarkCount).format[Int]
  )(ArticleHit.apply, unlift(ArticleHit.unapply))
}

case class ArticleSearchResult(
  last: Option[ExternalId[ArticleSearchResult]], // uuid of the last search. the frontend is responsible for tracking, this is meant for sessionization.
  query: String,
  hits: Seq[ArticleHit],
  myTotal: Int,
  friendsTotal: Int,
  othersTotal: Int,
  mayHaveMoreHits: Boolean,
  scorings: Seq[Scoring],
  filter: Set[Long],
  millisPassed: Int,
  pageNumber: Int,
  previousHits: Int,
  uuid: ExternalId[ArticleSearchResult] = ExternalId(),
  time: DateTime = currentDateTime,
  svVariance: Float = -1.0f, // semantic vector variance
  svExistenceVar: Float = -1.0f,
  toShow: Boolean = true,
  collections: Set[Long] = Set.empty[Long],
  lang: String = "en")

object ArticleSearchResult extends Logging {
  implicit val format: Format[ArticleSearchResult] = (
    (__ \ 'last).formatNullable(ExternalId.format[ArticleSearchResult]) and // uuid of the last search. the frontend is responsible for tracking and this is meant for sessionization.
    (__ \ 'query).format[String] and
    (__ \ 'hits).format(TraversableFormat.seq[ArticleHit]) and
    (__ \ 'myTotal).format[Int] and
    (__ \ 'friendsTotal).format[Int] and
    (__ \ 'othersTotal).format[Int] and
    (__ \ 'mayHaveMoreHits).format[Boolean] and
    (__ \ 'scorings).format(TraversableFormat.seq[Scoring]) and
    (__ \ 'filter).format[Set[Long]] and
    (__ \ 'millisPassed).format[Int] and
    (__ \ 'pageNumber).format[Int] and
    (__ \ 'previousHits).format[Int] and
    (__ \ 'uuid).format(ExternalId.format[ArticleSearchResult]) and
    (__ \ 'time).format[DateTime] and
    (__ \ 'svVariance).format[Float] and
    (__ \ 'svExistenceVar).format[Float] and
    (__ \ 'toShow).formatNullable[Boolean].inmap(_.getOrElse(true), Some.apply[Boolean]) and
    (__ \ 'collections).format[Set[Long]] and
    (__ \ 'lang).format[String]
  )(ArticleSearchResult.apply, unlift(ArticleSearchResult.unapply))
}

class Scoring(val textScore: Float, var normalizedTextScore: Float, val bookmarkScore: Float, val recencyScore: Float, val usefulPage: Boolean) extends Equals {
  def nonTextBoostFactor = (1.0d - (1.0d / (1.0d + (pow(4.0d * textScore, 4.0d))))).toFloat // don't boost too much by bookmark/recency if textScore is too low.
  var boostedTextScore: Float = Float.NaN
  var boostedBookmarkScore: Float = Float.NaN
  var boostedRecencyScore: Float = Float.NaN

  def score(textBoost: Float, bookmarkBoost: Float, recencyBoost: Float, usefulPageBoost: Float) = {
    boostedTextScore = normalizedTextScore * textBoost
    boostedBookmarkScore = bookmarkScore * bookmarkBoost
    boostedRecencyScore = recencyScore * recencyBoost

    (boostedTextScore + (boostedBookmarkScore + boostedRecencyScore) * nonTextBoostFactor) * (if (usefulPage) usefulPageBoost else 1.0f)
  }

  override def toString() = {
    s"Scoring($textScore, $normalizedTextScore, $bookmarkScore, $recencyScore, $usefulPage, $boostedTextScore, $boostedBookmarkScore, $boostedRecencyScore, nonTextBoostFactor)"
  }

  def canEqual(other: Any) = {
    other.isInstanceOf[com.keepit.search.Scoring]
  }

  override def equals(other: Any) = {
    other match {
      case that: com.keepit.search.Scoring =>
        that.canEqual(Scoring.this) && textScore == that.textScore && normalizedTextScore == that.normalizedTextScore && bookmarkScore == that.bookmarkScore &&
          recencyScore == that.recencyScore && usefulPage == that.usefulPage
      case _ => false
    }
  }

  override def hashCode() = {
    (textScore.hashCode ^ normalizedTextScore.hashCode ^ bookmarkScore.hashCode ^ recencyScore.hashCode ^ usefulPage.hashCode)
  }
}

object Scoring extends Logging {
  implicit val format: Format[Scoring] = new Format[Scoring] {
    def writes(res: Scoring): JsValue =
      try {
        JsObject(List(
          "textScore" -> JsNumber(res.textScore),
          "normalizedTextScore" -> JsNumber(res.normalizedTextScore),
          "bookmarkScore" -> JsNumber(res.bookmarkScore),
          "recencyScore" -> JsNumber(res.recencyScore),
          "boostedTextScore" -> (if (res.boostedTextScore.isNaN()) JsNull else JsNumber(res.boostedTextScore)),
          "boostedBookmarkScore" -> (if (res.boostedBookmarkScore.isNaN()) JsNull else JsNumber(res.boostedBookmarkScore)),
          "boostedRecencyScore" -> (if (res.boostedRecencyScore.isNaN()) JsNull else JsNumber(res.boostedRecencyScore)),
          "usefulPage" -> (if (!res.usefulPage) JsNull else JsBoolean(res.usefulPage))
        ))
      } catch {
        case e: Throwable =>
          log.error("can't serialize %s".format(res))
          throw e
      }

    def reads(json: JsValue): JsResult[Scoring] = JsSuccess({
      val score = new Scoring(
        textScore = (json \ "textScore").as[Float],
        normalizedTextScore = (json \ "normalizedTextScore").as[Float],
        bookmarkScore = (json \ "bookmarkScore").as[Float],
        recencyScore = (json \ "recencyScore").as[Float],
        usefulPage = (json \ "usefulPage").asOpt[Boolean].getOrElse(false)
      )
      score.boostedTextScore = (json \ "boostedTextScore").asOpt[Float].getOrElse(Float.NaN)
      score.boostedBookmarkScore = (json \ "boostedBookmarkScore").asOpt[Float].getOrElse(Float.NaN)
      score.boostedRecencyScore = (json \ "boostedRecencyScore").asOpt[Float].getOrElse(Float.NaN)
      score
    })
  }
}

