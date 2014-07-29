package com.keepit.model

import scala.concurrent.duration._
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.{ Logging, AccessLog }
import com.keepit.common.db._
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.heimdal.SanitizedKifiHit

case class Keep(
    id: Option[Id[Keep]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[Keep] = ExternalId(),
    title: Option[String] = None,
    uriId: Id[NormalizedURI],
    isPrimary: Boolean = true,
    urlId: Id[URL],
    url: String, // denormalized for efficiency
    bookmarkPath: Option[String] = None,
    isPrivate: Boolean = false, // This represents if the Keep is discoverable in search or the feed.
    userId: Id[User],
    state: State[Keep] = KeepStates.ACTIVE,
    source: KeepSource,
    kifiInstallation: Option[ExternalId[KifiInstallation]] = None,
    seq: SequenceNumber[Keep] = SequenceNumber.ZERO,
    libraryId: Option[Id[Library]]) extends ModelWithExternalId[Keep] with ModelWithState[Keep] with ModelWithSeqNumber[Keep] {

  override def toString: String = s"Bookmark[id:$id,externalId:$externalId,title:$title,uriId:$uriId,urlId:$urlId,url:$url,isPrivate:$isPrivate,isPrimary:$isPrimary,userId:$userId,state:$state,source:$source,seq:$seq],path:$bookmarkPath"

  def clean(): Keep = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  def withId(id: Id[Keep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withPrivate(isPrivate: Boolean) = copy(isPrivate = isPrivate)
  def withPrimary(isPrimary: Boolean) = copy(isPrimary = isPrimary)

  def withActive(isActive: Boolean) = copy(state = isActive match {
    case true => KeepStates.ACTIVE
    case false => KeepStates.INACTIVE
  })

  def withState(state: State[Keep]) = copy(state = state)

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withUrlId(urlId: Id[URL]) = copy(urlId = urlId)

  def withUrl(url: String) = copy(url = url)

  def withTitle(title: Option[String]) = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  def isActive: Boolean = state == KeepStates.ACTIVE

  def isDiscoverable = !isPrivate
}

object Keep {

  // is_primary: trueOrNull in db
  def applyWithPrimary(id: Option[Id[Keep]], createdAt: DateTime, updatedAt: DateTime, externalId: ExternalId[Keep], title: Option[String], uriId: Id[NormalizedURI], isPrimary: Option[Boolean], urlId: Id[URL], url: String, bookmarkPath: Option[String], isPrivate: Boolean, userId: Id[User], state: State[Keep], source: KeepSource, kifiInstallation: Option[ExternalId[KifiInstallation]], seq: SequenceNumber[Keep], libraryId: Option[Id[Library]]) =
    Keep(id, createdAt, updatedAt, externalId, title, uriId, isPrimary.exists(b => b), urlId, url, bookmarkPath, isPrivate, userId, state, source, kifiInstallation, seq, libraryId)
  def unapplyWithPrimary(k: Keep) = {
    Some(k.id, k.createdAt, k.updatedAt, k.externalId, k.title, k.uriId, if (k.isPrimary) Some(true) else None, k.urlId, k.url, k.bookmarkPath, k.isPrivate, k.userId, k.state, k.source, k.kifiInstallation, k.seq, k.libraryId)
  }

  implicit def bookmarkFormat = (
    (__ \ 'id).formatNullable(Id.format[Keep]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[Keep]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'isPrimary).format[Boolean] and
    (__ \ 'urlId).format(Id.format[URL]) and
    (__ \ 'url).format[String] and
    (__ \ 'bookmarkPath).formatNullable[String] and
    (__ \ 'isPrivate).format[Boolean] and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'state).format(State.format[Keep]) and
    (__ \ 'source).format[String].inmap(KeepSource.apply, unlift(KeepSource.unapply)) and
    (__ \ 'kifiInstallation).formatNullable(ExternalId.format[KifiInstallation]) and
    (__ \ 'seq).format(SequenceNumber.format[Keep]) and
    (__ \ 'libraryId).formatNullable(Id.format[Library])
  )(Keep.apply, unlift(Keep.unapply))
}

case class KeepUriAndTime(uriId: Id[NormalizedURI], createdAt: DateTime = currentDateTime)

object KeepUriAndTime {
  import com.keepit.common.time.internalTime.DateTimeJsonLongFormat

  implicit def bookmarkUriAndTimeFormat = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'createdAt).format(DateTimeJsonLongFormat)
  )(KeepUriAndTime.apply, unlift(KeepUriAndTime.unapply))
}

case class KeepCountKey(userId: Option[Id[User]] = None) extends Key[Int] {
  override val version = 3
  val namespace = "bookmark_count"
  def toKey(): String = userId map (_.toString) getOrElse "all"
}

class KeepCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[KeepCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class KeepUriUserKey(uriId: Id[NormalizedURI], userId: Id[User]) extends Key[Keep] {
  override val version = 6
  val namespace = "bookmark_uri_user"
  def toKey(): String = uriId.id + "#" + userId.id
}

class KeepUriUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[KeepUriUserKey, Keep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LatestKeepUriKey(uriId: Id[NormalizedURI]) extends Key[Keep] {
  override val version = 4
  val namespace = "latest_keep_uri"
  def toKey(): String = uriId.toString
}

class LatestKeepUriCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LatestKeepUriKey, Keep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LatestKeepUrlKey(url: String) extends Key[Keep] {
  override val version = 3
  val namespace = "latest_keep_url"
  def toKey(): String = NormalizedURI.hashUrl(url).hash
}

class LatestKeepUrlCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LatestKeepUrlKey, Keep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class KifiHitKey(userId: Id[User], uriId: Id[NormalizedURI]) extends Key[SanitizedKifiHit] {
  override val version = 2
  val namespace = "keep_hit"
  def toKey(): String = userId.id + "#" + uriId.id
}

class KifiHitCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[KifiHitKey, SanitizedKifiHit](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object KeepStates extends States[Keep] {
  val DUPLICATE = State[Keep]("duplicate")
}

case class KeepSource(value: String) {
  override def toString = value
}

object KeepSource {
  val keeper = KeepSource("keeper")
  val bookmarkImport = KeepSource("bookmarkImport")
  val bookmarkFileImport = KeepSource("bookmarkFileImport")
  val site = KeepSource("site")
  val mobile = KeepSource("mobile")
  val email = KeepSource("email")
  val default = KeepSource("default")
  val unknown = KeepSource("unknown")
  val kippt = KeepSource("Kippt")
  val pocket = KeepSource("Pocket")
  val instapaper = KeepSource("Instapaper")

  val valid = Set(keeper, bookmarkImport, site, mobile, email, default, bookmarkFileImport, kippt, pocket, instapaper)

  val imports = Set(bookmarkImport, kippt, pocket, instapaper, bookmarkFileImport)

  def get(value: String): KeepSource = KeepSource(value) match {
    case KeepSource("HOVER_KEEP") => keeper
    case KeepSource("INIT_LOAD") => bookmarkImport
    case source => source
  }
}

object KeepFactory extends Logging {

  def apply(origUrl: String, uri: NormalizedURI, userId: Id[User], title: Option[String], url: URL, source: KeepSource, isPrivate: Boolean = false, kifiInstallation: Option[ExternalId[KifiInstallation]] = None, libraryId: Option[Id[Library]]): Keep = {
    Keep(title = title, userId = userId, uriId = uri.id.get, urlId = url.id.get, url = origUrl, source = source, isPrivate = isPrivate, libraryId = libraryId)
  }

}
