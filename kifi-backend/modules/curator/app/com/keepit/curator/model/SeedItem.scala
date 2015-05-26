package com.keepit.curator.model

import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.model.{ NormalizedURI, User }
import com.kifi.macros.json

import play.api.libs.json._
import play.api.libs.functional.syntax._

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
  lastKept: DateTime,
  keepers: Keepers,
  discoverable: Boolean)

case class PublicSeedItem(
  uriId: Id[NormalizedURI],
  url: String,
  seq: SequenceNumber[PublicSeedItem],
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers,
  discoverable: Boolean)

@json case class PublicUriScores(
  popularityScore: Float,
  recencyScore: Float,
  rekeepScore: Float,
  discoveryScore: Float,
  curationScore: Option[Float],
  multiplier: Option[Float])

case class UriScores(
    socialScore: Float,
    popularityScore: Float,
    overallInterestScore: Float,
    recentInterestScore: Float,
    recencyScore: Float,
    priorScore: Float,
    rekeepScore: Float,
    discoveryScore: Float,
    curationScore: Option[Float],
    multiplier: Option[Float],
    libraryInducedScore: Option[Float],
    topic1Multiplier: Option[Float] = None,
    topic1: Option[Int] = None) {

  override def toString = f"""
    |s:$socialScore%1.2f-
    |p:$popularityScore%1.2f-
    |oI:$overallInterestScore%1.2f-
    |rI:$recentInterestScore%1.2f-
    |r:$recencyScore%1.2f-
    |g:$priorScore%1.2f-
    |rk:$rekeepScore%1.2f-
    |d:$discoveryScore%1.2f-
    |c:${curationScore.getOrElse(0.0f)}%1.2f-
    |m:${multiplier.getOrElse(1.0f)}%1.2f-
    |lb:${libraryInducedScore.getOrElse(0.0f)}%1.2f-
    |t1m:${topic1Multiplier.getOrElse(0.0f)}%1.2f-
    |t1:${topic1.getOrElse("")}
  """.stripMargin('|').replace("\n", "").trim()

  private def reducePrecision(x: Float): Float = {
    (x * 10000).toInt.toFloat / 10000
  }

  private def reducePrecision(xOpt: Option[Float]): Option[Float] = {
    xOpt.map { x => (x * 10000).toInt.toFloat / 10000 }
  }

  //this is used to save space in the json
  def withReducedPrecision(): UriScores = UriScores(
    reducePrecision(socialScore),
    reducePrecision(popularityScore),
    reducePrecision(overallInterestScore),
    reducePrecision(recentInterestScore),
    reducePrecision(recencyScore),
    reducePrecision(priorScore),
    reducePrecision(rekeepScore),
    reducePrecision(discoveryScore),
    reducePrecision(curationScore),
    reducePrecision(multiplier),
    reducePrecision(libraryInducedScore),
    reducePrecision(topic1Multiplier),
    topic1
  )

  def prettyJson(): JsValue = {
    val QUOTE = "\""
    def writeField(name: String, valueOpt: Option[Float]): Option[String] = {
      valueOpt.map { value => QUOTE + name + QUOTE + ":" + value.toString }
    }

    def writeIntField(name: String, valueOpt: Option[Int]): Option[String] = {
      valueOpt.map { value => QUOTE + name + QUOTE + ":" + value.toString }
    }

    val reduced = this.withReducedPrecision()
    val lst = List(writeField("s", Some(reduced.socialScore)), writeField("p", Some(reduced.popularityScore)), writeField("oI", Some(reduced.overallInterestScore)), writeField("rI", Some(reduced.recentInterestScore)),
      writeField("r", Some(reduced.recencyScore)), writeField("g", Some(reduced.priorScore)), writeField("rk", Some(reduced.rekeepScore)), writeField("d", Some(reduced.discoveryScore)),
      writeField("c", reduced.curationScore), writeField("m", reduced.multiplier), writeField("lb", reduced.libraryInducedScore), writeField("t1m", reduced.topic1Multiplier), writeIntField("t1", reduced.topic1))

    Json.parse(lst.flatten.mkString("{", ",", "}"))
  }
}

object UriScores {
  val oldFormat = Json.format[UriScores]

  def newFormat = (
    (__ \ 's).format[Float] and
    (__ \ 'p).format[Float] and
    (__ \ 'oI).format[Float] and
    (__ \ 'rI).format[Float] and
    (__ \ 'r).format[Float] and
    (__ \ 'g).format[Float] and
    (__ \ 'rk).format[Float] and
    (__ \ 'd).format[Float] and
    (__ \ 'c).formatNullable[Float] and
    (__ \ 'm).formatNullable[Float] and
    (__ \ 'lb).formatNullable[Float] and
    (__ \ 't1m).formatNullable[Float] and
    (__ \ 't1).formatNullable[Int]
  )(UriScores.apply, unlift(UriScores.unapply))

  implicit val format: Format[UriScores] = new Format[UriScores] {
    def reads(json: JsValue) = {
      oldFormat.reads(json) orElse newFormat.reads(json)
    }

    def writes(obj: UriScores): JsValue = {
      obj.prettyJson()
    }
  }
}

case class SeedItemWithMultiplier(
    multiplier: Float = 1.0f,
    userId: Id[User],
    uriId: Id[NormalizedURI],
    priorScore: Option[Float] = None,
    timesKept: Int,
    lastSeen: DateTime,
    keepers: Keepers) {
  def makePublicSeedItemWithMultiplier = PublicSeedItemWithMultiplier(multiplier, uriId, timesKept, lastSeen, keepers)
}

case class PublicSeedItemWithMultiplier(
  multiplier: Float = 1.0f,
  uriId: Id[NormalizedURI],
  timesKept: Int,
  lastSeen: DateTime,
  keepers: Keepers)

case class PublicScoredSeedItem(uriId: Id[NormalizedURI], publicUriScores: PublicUriScores)

case class ScoredSeedItem(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores,
  topic1: Option[LDATopic], topic2: Option[LDATopic])

case class ScoredSeedItemWithAttribution(userId: Id[User], uriId: Id[NormalizedURI], uriScores: UriScores,
  attribution: SeedAttribution, topic1: Option[LDATopic], topic2: Option[LDATopic])
