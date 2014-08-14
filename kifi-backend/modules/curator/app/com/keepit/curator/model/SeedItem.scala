package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ Keep, NormalizedURI, User }
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
  keepers: Keepers,
  discoverable: Boolean)

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

@json case class UserAttribution(friends: Seq[Id[User]], others: Int)
@json case class KeepAttribution(keeps: Seq[Id[Keep]])
@json case class TopicAttribution(topicName: String)
@json case class SeedAttribution(user: Option[UserAttribution] = None, keep: Option[KeepAttribution] = None, topic: Option[TopicAttribution] = None)

object SeedAttribution {
  val EMPTY = SeedAttribution()
}

case class ScoredSeedItemWithAttribution(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores, attribution: SeedAttribution)
