package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import com.keepit.common.strings._
import play.api.libs.json.JsString

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURI (
  id: Option[Id[NormalizedURI]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[NormalizedURI] = ExternalId(),
  title: Option[String] = None,
  url: String,
  urlHash: UrlHash,
  state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
  seq: SequenceNumber = SequenceNumber.ZERO,
  screenshotUpdatedAt: Option[DateTime] = None,
  normalization: Option[Normalization] = None,
  redirect: Option[Id[NormalizedURI]] = None,
  redirectTime: Option[DateTime] = None
) extends ModelWithExternalId[NormalizedURI] with Logging {
  def withId(id: Id[NormalizedURI]): NormalizedURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): NormalizedURI = copy(updatedAt = now)
  def withState(state: State[NormalizedURI]) = copy(state = state)
  def withTitle(title: String) = if (title.isEmpty()) this else copy(title = Some(title))
  def withNormalization(normalization: Normalization) = copy(normalization = Some(normalization))
  def withRedirect(id: Id[NormalizedURI], now: DateTime): NormalizedURI = copy(redirect = Some(id), redirectTime = Some(now))
}

object NormalizedURI {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[NormalizedURI]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'urlHash).format[String].inmap(UrlHash.apply, unlift(UrlHash.unapply)) and
    (__ \ 'state).format(State.format[NormalizedURI]) and
    (__ \ 'seq).format(SequenceNumber.sequenceNumberFormat) and
    (__ \ 'screenshotUpdatedAt).formatNullable[DateTime] and
    (__ \ 'normalization).formatNullable[Normalization] and
    (__ \ 'redirect).formatNullable(Id.format[NormalizedURI]) and
    (__ \'redirectTime).formatNullable[DateTime]
    )(NormalizedURI.apply, unlift(NormalizedURI.unapply))

  def hashUrl(normalizedUrl: String): UrlHash = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalizedUrl)
    UrlHash(new String(new Base64().encode(binaryHash), UTF8))
  }

  def withHash(
    normalizedUrl: String,
    title: Option[String] = None,
    state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
    normalization: Option[Normalization] = None
    ): NormalizedURI = {
    if (normalizedUrl.size > URLFactory.MAX_URL_SIZE) throw new Exception(s"url size is ${normalizedUrl.size} which exceeds ${URLFactory.MAX_URL_SIZE}: $normalizedUrl")
    NormalizedURI(title = title, url = normalizedUrl, urlHash = hashUrl(normalizedUrl), state = state, screenshotUpdatedAt = None, normalization = normalization)
  }
}

case class UrlHash(hash: String) extends AnyVal

case class NormalizedURIKey(id: Id[NormalizedURI]) extends Key[NormalizedURI] {
  override val version = 2
  val namespace = "uri_by_id"
  def toKey(): String = id.id.toString
}

case class NormalizedURIUrlHashKey(urlHash: UrlHash) extends Key[NormalizedURI] {
  override val version = 1
  val namespace = "uri_by_hash"
  def toKey(): String = urlHash.hash
}

class NormalizedURICache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NormalizedURIKey, NormalizedURI](innermostPluginSettings, innerToOuterPluginSettings:_*)

class NormalizedURIUrlHashCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NormalizedURIUrlHashKey, NormalizedURI](innermostPluginSettings, innerToOuterPluginSettings:_*)

object NormalizedURIStates extends States[NormalizedURI] {
  val SCRAPED	= State[NormalizedURI]("scraped")
  val SCRAPE_FAILED = State[NormalizedURI]("scrape_failed")
  val UNSCRAPABLE = State[NormalizedURI]("unscrapable")
  val SCRAPE_WANTED = State[NormalizedURI]("scrape_wanted")

  type Transitions = Map[State[NormalizedURI], Set[State[NormalizedURI]]]

  val ALL_TRANSITIONS: Transitions = Map(
      (ACTIVE -> Set(SCRAPE_WANTED)),
      (SCRAPE_WANTED -> Set(SCRAPED, SCRAPE_FAILED, UNSCRAPABLE, INACTIVE)),
      (SCRAPED -> Set(SCRAPE_WANTED, INACTIVE)),
      (SCRAPE_FAILED -> Set(SCRAPE_WANTED, INACTIVE)),
      (UNSCRAPABLE -> Set(SCRAPE_WANTED, INACTIVE)),
      (INACTIVE -> Set(SCRAPE_WANTED, ACTIVE, INACTIVE)))

  val ADMIN_TRANSITIONS: Transitions = Map(
      (ACTIVE -> Set.empty),
      (SCRAPED -> Set(ACTIVE)),
      (SCRAPE_FAILED -> Set(ACTIVE)),
      (UNSCRAPABLE -> Set(ACTIVE)),
      (INACTIVE -> Set.empty))

  def transitionByAdmin[T](transition: (State[NormalizedURI], Set[State[NormalizedURI]]))(f:State[NormalizedURI]=>T) = {
    f(validate(transition, ADMIN_TRANSITIONS))
  }

  def findNextState(transition: (State[NormalizedURI], Set[State[NormalizedURI]])) = validate(transition, ALL_TRANSITIONS)

  private def validate(transition: (State[NormalizedURI], Set[State[NormalizedURI]]), transitions: Transitions): State[NormalizedURI] = {
    transition match {
      case (from, to) =>
        transitions.get(from) match {
          case Some(possibleStates) =>
            (possibleStates intersect to).headOption.getOrElse(throw new StateException("invalid transition: %s -> %s".format(from, to)))
          case None => throw new StateException("no such state: %s".format(from))
        }
    }
  }
}


