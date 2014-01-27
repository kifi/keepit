package com.keepit.model

import scala.concurrent.duration._
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class Bookmark(
  id: Option[Id[Bookmark]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Bookmark] = ExternalId(),
  title: Option[String] = None,
  uriId: Id[NormalizedURI],
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  url: String, // denormalized for efficiency
  bookmarkPath: Option[String] = None,
  isPrivate: Boolean = false,
  userId: Id[User],
  state: State[Bookmark] = BookmarkStates.ACTIVE,
  source: BookmarkSource,
  kifiInstallation: Option[ExternalId[KifiInstallation]] = None,
  seq: SequenceNumber = SequenceNumber.ZERO
) extends ModelWithExternalId[Bookmark] with ModelWithState[Bookmark] {

  override def toString: String = s"Bookmark[id:$id,externalId:$externalId,title:$title,uriId:$uriId,urlId:$urlId,url:$url,isPrivate:$isPrivate,userId:$userId,state:$state,source:$source,seq:$seq],path:$bookmarkPath"

  def clean(): Bookmark = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  def withId(id: Id[Bookmark]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withPrivate(isPrivate: Boolean) = copy(isPrivate = isPrivate)

  def withActive(isActive: Boolean) = copy(state = isActive match {
    case true => BookmarkStates.ACTIVE
    case false => BookmarkStates.INACTIVE
  })

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))

  def withUrl(url: String) = copy(url = url)

  def withTitle(title: Option[String]) = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  def isActive: Boolean = state == BookmarkStates.ACTIVE
}

object Bookmark {
  implicit def bookmarkFormat = (
    (__ \ 'id).formatNullable(Id.format[Bookmark]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[Bookmark]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'urlId).formatNullable(Id.format[URL]) and
    (__ \ 'url).format[String] and
    (__ \ 'bookmarkPath).formatNullable[String] and
    (__ \ 'isPrivate).format[Boolean] and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'state).format(State.format[Bookmark]) and
    (__ \ 'source).format[String].inmap(BookmarkSource.apply, unlift(BookmarkSource.unapply)) and
    (__ \ 'kifiInstallation).formatNullable(ExternalId.format[KifiInstallation]) and
    (__ \ 'seq).format(SequenceNumber.sequenceNumberFormat)
  )(Bookmark.apply, unlift(Bookmark.unapply))
}

case class BookmarkUriAndTime(uriId: Id[NormalizedURI], createdAt: DateTime = currentDateTime)

object BookmarkUriAndTime {
  import com.keepit.common.time.internalTime.DateTimeJsonLongFormat

  implicit def bookmarkUriAndTimeFormat = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'createdAt).format(DateTimeJsonLongFormat)
  )(BookmarkUriAndTime.apply, unlift(BookmarkUriAndTime.unapply))
}

case class BookmarkCountKey(userId: Option[Id[User]] = None) extends Key[Int] {
  override val version = 3
  val namespace = "bookmark_count"
  def toKey(): String = userId map (_.toString) getOrElse "all"
}

class BookmarkCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[BookmarkCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class BookmarkUriUserKey(uriId: Id[NormalizedURI], userId: Id[User]) extends Key[Bookmark] {
  override val version = 4
  val namespace = "bookmark_uri_user"
  def toKey(): String = uriId.id + "#" + userId.id
}

class BookmarkUriUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BookmarkUriUserKey, Bookmark](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class LatestBookmarkUriKey(uriId: Id[NormalizedURI]) extends Key[Bookmark] {
  override val version = 1
  val namespace = "latest_bookmark_uri"
  def toKey(): String = uriId.toString
}

class LatestBookmarkUriCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LatestBookmarkUriKey, Bookmark](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

object BookmarkStates extends States[Bookmark]

case class BookmarkSource(value: String) {
  override def toString = value
}

object BookmarkSource {
  val keeper = BookmarkSource("keeper")
  val bookmarkImport = BookmarkSource("bookmarkImport")
  val site = BookmarkSource("site")
  val mobile = BookmarkSource("mobile")
  val email = BookmarkSource("email")
  val default = BookmarkSource("default")
  val unknown = BookmarkSource("unknown")

  val valid = Set(keeper, bookmarkImport, site, mobile, email, default)

  def get(value: String): BookmarkSource = BookmarkSource(value) match {
    case BookmarkSource("HOVER_KEEP") => keeper
    case BookmarkSource("INIT_LOAD") => bookmarkImport
    case source => source
  }
}

object BookmarkFactory {

  def apply(uri: NormalizedURI, userId: Id[User], title: Option[String], url: URL, source: BookmarkSource, isPrivate: Boolean = false, kifiInstallation: Option[ExternalId[KifiInstallation]] = None): Bookmark =
    Bookmark(title = title, userId = userId, uriId = uri.id.get, urlId = Some(url.id.get), url = url.url, source = source, isPrivate = isPrivate)

}
