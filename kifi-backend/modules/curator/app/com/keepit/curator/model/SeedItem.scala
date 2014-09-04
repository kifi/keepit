package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ Library, NormalizedURI, User }
import com.kifi.macros.json

import org.joda.time.DateTime

sealed trait Keepers
object Keepers {
  case object TooMany extends Keepers
  case class ReasonableNumber(who: Seq[Id[User]]) extends Keepers
}

trait SeedItemType

case class SeedItem(
  userId: Id[User],
  uriId: Id[NormalizedURI],
  url: String,
  seq: SequenceNumber[SeedItem],
  priorScore: Option[Float],
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers,

  discoverable: Boolean) extends SeedItemType

case class PublicSeedItem(
  uriId: Id[NormalizedURI],
  url: String,
  seq: SequenceNumber[PublicSeedItem],
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers,
  discoverable: Boolean) extends SeedItemType

@json case class PublicUriScores(
  popularityScore: Float,
  recencyScore: Float,
  rekeepScore: Float,
  discoveryScore: Float,
  curationScore: Option[Float],
  multiplier: Option[Float])

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

trait SeedItemWithMultiplierType {
  def multiplier: Float
  def timesKept: Int
  def lastSeen: DateTime
}

case class SeedItemWithMultiplier(
    override val multiplier: Float = 1.0f,
    userId: Id[User],
    uriId: Id[NormalizedURI],
    priorScore: Option[Float] = None,
    override val timesKept: Int,
    override val lastSeen: DateTime,
    keepers: Keepers,
    libraries: Seq[Id[Library]]) extends SeedItemWithMultiplierType {
  def makePublicSeedItemWithMultiplier = PublicSeedItemWithMultiplier(multiplier, uriId, timesKept, lastSeen, keepers)
}

case class PublicSeedItemWithMultiplier(
  override val multiplier: Float = 1.0f,
  uriId: Id[NormalizedURI],
  override val timesKept: Int,
  override val lastSeen: DateTime,
  keepers: Keepers) extends SeedItemWithMultiplierType

case class PublicScoredSeedItem(uriId: Id[NormalizedURI], publicUriScores: PublicUriScores)

case class ScoredSeedItem(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores)

case class ScoredSeedItemWithAttribution(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores, attribution: SeedAttribution)
