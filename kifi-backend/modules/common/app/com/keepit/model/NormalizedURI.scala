package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.common.cache._
import org.joda.time.DateTime
import scala.concurrent.duration._
import com.keepit.common.net.URINormalizer
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import scala.Some
import com.keepit.common.strings._

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURI (
  id: Option[Id[NormalizedURI]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[NormalizedURI] = ExternalId(),
  title: Option[String] = None,
  url: String,
  urlHash: String,
  state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
  seq: SequenceNumber = SequenceNumber.ZERO
) extends ModelWithExternalId[NormalizedURI] with Logging {
  def withId(id: Id[NormalizedURI]): NormalizedURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): NormalizedURI = copy(updatedAt = now)

  def withState(state: State[NormalizedURI]) = copy(state = state)
  def withTitle(title: String) = if (title.isEmpty()) this else copy(title = Some(title))
}

import com.keepit.serializer.NormalizedURISerializer.normalizedURISerializer // Required implicit value
case class NormalizedURIKey(id: Id[NormalizedURI]) extends Key[NormalizedURI] {
  override val version = 2
  val namespace = "uri_by_id"
  def toKey(): String = id.id.toString
}
class NormalizedURICache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NormalizedURIKey, NormalizedURI](innermostPluginSettings, innerToOuterPluginSettings:_*)

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

object NormalizedURIFactory {

  def apply(url: String): NormalizedURI =
    apply(title = None, url = url, state = NormalizedURIStates.ACTIVE)

  def apply(url: String, state: State[NormalizedURI]): NormalizedURI =
    apply(title = None, url = url, state = state)

  def apply(title: String, url: String): NormalizedURI =
    NormalizedURIFactory(title = Some(title), url = url, state = NormalizedURIStates.ACTIVE)

  def apply(title: String, url: String, state: State[NormalizedURI]): NormalizedURI =
    NormalizedURIFactory(title = Some(title), url = url, state = state)

  def apply(title: Option[String], url: String, state: State[NormalizedURI]): NormalizedURI = {
    val normalized = normalize(url)
    if (normalized.size > URLFactory.MAX_URL_SIZE) throw new Exception(s"url size is ${normalized.size} which exceeds ${URLFactory.MAX_URL_SIZE}: $normalized")
    NormalizedURI(title = title, url = normalized, urlHash = hashUrl(normalized), state = state)
  }

  def normalize(url: String) = URINormalizer.normalize(url)

  def hashUrl(normalizedUrl: String): String = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(normalizedUrl)
    new String(new Base64().encode(binaryHash), UTF8)
  }
}