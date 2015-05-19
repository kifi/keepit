package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.logging.Logging
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64
import com.keepit.common.strings._

case class URISearchResults(uri: NormalizedURI, score: Float)

case class NormalizedURI(
    id: Option[Id[NormalizedURI]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[NormalizedURI] = ExternalId(),
    title: Option[String] = None,
    url: String,
    urlHash: UrlHash,
    state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
    seq: SequenceNumber[NormalizedURI] = SequenceNumber.ZERO,
    restriction: Option[Restriction] = None,
    normalization: Option[Normalization] = None,
    redirect: Option[Id[NormalizedURI]] = None,
    redirectTime: Option[DateTime] = None,
    shouldHaveContent: Boolean = false) extends ModelWithExternalId[NormalizedURI] with ModelWithState[NormalizedURI] with ModelWithSeqNumber[NormalizedURI] with Logging {

  def withId(id: Id[NormalizedURI]): NormalizedURI = copy(id = Some(id))
  def withUpdateTime(now: DateTime): NormalizedURI = copy(updatedAt = now)
  def withState(state: State[NormalizedURI]) = copy(state = state)
  def withTitle(title: String) = {
    val cleanTitle = title.trimAndRemoveLineBreaks()
    if (cleanTitle.isEmpty()) this else copy(title = Some(cleanTitle))
  }
  def withNormalization(normalization: Normalization) = copy(normalization = Some(normalization))
  def withRedirect(id: Id[NormalizedURI], now: DateTime): NormalizedURI = copy(state = NormalizedURIStates.REDIRECTED, redirect = Some(id), redirectTime = Some(now))
  def toShortString = s"NormalizedUri($id,$seq,${state.toString.toUpperCase},${restriction.getOrElse("N/A")},${normalization},${redirect},${url.take(50)})"
  def withContentRequest(contentWanted: Boolean) = if (contentWanted) copy(shouldHaveContent = true) else this
}

object NormalizedURI {
  implicit val nIdFormat = Id.format[NormalizedURI]
  implicit val extIdFormat = ExternalId.format[NormalizedURI]
  implicit val stateFormat = State.format[NormalizedURI]
  implicit val urlHashFormat = Format(
    __.read[String].map(UrlHash(_)),
    new Writes[UrlHash] { def writes(o: UrlHash) = JsString(o.hash) }
  )

  val TitleMaxLen = 2040
  val UrlMaxLen = 3000

  val handleDeprecatedScrapeWantedState: State[NormalizedURI] => State[NormalizedURI] = {
    case State("scrape_wanted") => NormalizedURIStates.ACTIVE
    case state => state
  }
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[NormalizedURI]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'urlHash).format[UrlHash] and
    (__ \ 'state).format(State.format[NormalizedURI]).inmap(handleDeprecatedScrapeWantedState, identity[State[NormalizedURI]]) and
    (__ \ 'seq).format(SequenceNumber.format[NormalizedURI]) and
    (__ \ 'restriction).formatNullable[Restriction] and
    (__ \ 'normalization).formatNullable[Normalization] and
    (__ \ 'redirect).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'redirectTime).formatNullable[DateTime] and
    (__ \ 'shouldHaveContent).format[Boolean]
  )(NormalizedURI.apply, unlift(NormalizedURI.unapply))

  def withHash(
    normalizedUrl: String,
    title: Option[String] = None,
    state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
    normalization: Option[Normalization] = None): NormalizedURI = {
    if (normalizedUrl.size > URLFactory.MAX_URL_SIZE) throw new Exception(s"url size is ${normalizedUrl.size} which exceeds ${URLFactory.MAX_URL_SIZE}: $normalizedUrl")
    NormalizedURI(title = title, url = normalizedUrl, urlHash = UrlHash.hash(normalizedUrl), state = state, normalization = normalization)
  }
}

case class UrlHash(hash: String) extends AnyVal {
  override def toString: String = hash
}

object UrlHash {
  def hash(url: String): UrlHash = {
    val binaryHash = MessageDigest.getInstance("MD5").digest(url)
    UrlHash(new String(new Base64().encode(binaryHash), UTF8))
  }

  implicit val format: Format[UrlHash] = new Format[UrlHash] {
    def reads(json: JsValue): JsResult[UrlHash] = json.validate[String].map(UrlHash.apply)
    def writes(o: UrlHash): JsValue = JsString(o.hash)
  }
}

case class NormalizedURIKey(id: Id[NormalizedURI]) extends Key[NormalizedURI] {
  override val version = 8
  val namespace = "uri_by_id"
  def toKey(): String = id.id.toString
}

case class NormalizedURIUrlHashKey(urlHash: UrlHash) extends Key[NormalizedURI] {
  override val version = 7
  val namespace = "uri_by_hash"
  def toKey(): String = urlHash.hash
}

class NormalizedURICache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NormalizedURIKey, NormalizedURI](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class NormalizedURIUrlHashCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NormalizedURIUrlHashKey, NormalizedURI](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object NormalizedURIStates extends States[NormalizedURI] {
  val SCRAPED = State[NormalizedURI]("scraped")
  val SCRAPE_FAILED = State[NormalizedURI]("scrape_failed")
  val UNSCRAPABLE = State[NormalizedURI]("unscrapable")
  val REDIRECTED = State[NormalizedURI]("redirected")

  val DO_NOT_SCRAPE = Set(INACTIVE, UNSCRAPABLE, REDIRECTED)
}

case class IndexableUri(
  id: Option[Id[NormalizedURI]] = None,
  title: Option[String] = None,
  url: String,
  restriction: Option[Restriction] = None,
  state: State[NormalizedURI] = NormalizedURIStates.ACTIVE,
  shouldHaveContent: Boolean,
  seq: SequenceNumber[NormalizedURI])

object IndexableUri {

  def apply(uri: NormalizedURI): IndexableUri = IndexableUri(uri.id, uri.title, uri.url, uri.restriction, uri.state, uri.shouldHaveContent, uri.seq)

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'restriction).formatNullable[Restriction] and
    (__ \ 'state).format(State.format[NormalizedURI]) and
    (__ \ 'shouldHaveContent).format[Boolean] and
    (__ \ 'seq).format(SequenceNumber.format[NormalizedURI])
  )(IndexableUri.apply, unlift(IndexableUri.unapply))
}
