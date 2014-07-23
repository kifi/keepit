package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.model.{ NormalizedURI, User }

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
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers)

case class UriScores(
  socialScore: Float, //s
  popularityScore: Float, //k
  overallInterestScore: Float, //i
  recencyScore: Float, //r
  priorScore: Float //p
  )

case class ScoredSeedItem(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores)
