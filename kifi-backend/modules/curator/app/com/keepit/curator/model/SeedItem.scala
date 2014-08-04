package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ NormalizedURI, User }
import com.kifi.macros.json

import org.joda.time.DateTime
import play.api.libs.json._

sealed trait Keepers
object Keepers {
  case object TooMany extends Keepers
  case class ReasonableNumber(who: Seq[Id[User]]) extends Keepers
}

case class SeedItem(
  userId: Id[User],
  uriId: Id[NormalizedURI],
  seq: SequenceNumber[SeedItem],
  priorScore: Option[Float],
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers)

//object UriScoresSerializer extends Format[UriScores] {
//
//  def writes(uriScores: UriScores): JsValue =
//    JsObject(List(
//      "socialScore" -> JsNumber(uriScores.socialScore),
//      "popularityScore" -> JsNumber(uriScores.popularityScore),
//      "overallInterestScore" -> JsNumber(uriScores.overallInterestScore),
//      "recentInterestScore" -> JsNumber(uriScores.recentInterestScore),
//      "recencyScore" -> JsNumber(uriScores.recencyScore),
//      "priorScore" -> JsNumber(uriScores.priorScore),
//      "rekeepScore" -> JsNumber(uriScores.priorScore),
//      "discoveryScore" -> JsNumber(uriScores.priorScore)
//    ))
//
//  def reads(json: JsValue): JsSuccess[UriScores] = JsSuccess({
//    UriScores(
//      socialScore = (json \ "socialScore").as[Float],
//      popularityScore = (json \ "popularityScore").as[Float],
//      overallInterestScore = (json \ "overallInterestScore").as[Float],
//      recentInterestScore = (json \ "recentInterestScore").as[Float],
//      recencyScore = (json \ "recencyScore").as[Float],
//      priorScore = (json \ "priorScore").as[Float],
//      rekeepScore = (json \ "rekeepScore").as[Float],
//      discoveryScore = (json \ "discoveryScore").as[Float]
//    )
//  })
//}

@json case class UriScores(
    socialScore: Float,
    popularityScore: Float,
    overallInterestScore: Float,
    recentInterestScore: Float,
    recencyScore: Float,
    priorScore: Float,
    rekeepScore: Float,
    discoveryScore: Float) {

  override def toString = s"social:$socialScore --- popularity:$popularityScore --- overallInterest:$overallInterestScore --- recentInterest:$recentInterestScore --- recency:$recencyScore --- prior:$priorScore --- rekeep:$rekeepScore --- discovery:$discoveryScore"
}

case class ScoredSeedItem(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores)
