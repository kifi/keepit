package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ NormalizedURI, User }

import com.kifi.macros.json

import org.joda.time.DateTime

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

case class UriScores(
    socialScore: Float,
    popularityScore: Float,
    overallInterestScore: Float,
    recentInterestScore: Float,
    recencyScore: Float,
    priorScore: Float) {

  override def toString = s"social:$socialScore --- popularity:$popularityScore --- overallInterest:$overallInterestScore --- recentInterest:$recentInterestScore --- recency:$recencyScore --- prior:$priorScore"
}

case class ScoredSeedItem(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores)
