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
  url: String,
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
    discoveryScore: Float,
    curationScore: Option[Float],
    multiplier: Option[Float]) {

  override def toString = f"""
    s:$socialScore%1.2f-
    p:$popularityScore%1.2f-
    oI:$overallInterestScore%1.2f-
    rI:$recentInterestScore%1.2f-
    r:$recencyScore%1.2f-
    g:$priorScore%1.2f-
    rk:$rekeepScore%1.2f-
    d:$discoveryScore%1.2f-
    c:${curationScore.getOrElse(0.0f)}%1.2f-
    m:${multiplier.getOrElse(1.0f)}%1.2f
  """.replace("\n", "").trim
}

case class SeedItemWithMultiplier(
  multiplier: Float = 1.0f,
  userId: Id[User],
  uriId: Id[NormalizedURI],
  priorScore: Option[Float],
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers)

case class ScoredSeedItem(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores)

case class ScoredSeedItemWithAttribution(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores, attribution: SeedAttribution)
