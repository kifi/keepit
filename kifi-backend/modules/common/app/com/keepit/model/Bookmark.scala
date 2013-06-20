package com.keepit.model

import scala.concurrent.duration._
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.db._
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
) extends ModelWithExternalId[Bookmark] {
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

  def withTitle(title: Option[String]) = copy(title = title)

  def isActive: Boolean = state == BookmarkStates.ACTIVE
}

object Bookmark {
  implicit def bookmarkFormat = (
    (__ \ 'id).formatNullable(Id.format[Bookmark]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
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

case class BookmarkCountKey() extends Key[Int] {
  override val version = 2
  val namespace = "bookmark_count"
  def toKey(): String = "k"
}

class BookmarkCountCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[BookmarkCountKey, Int](innermostPluginSettings, innerToOuterPluginSettings:_*)

case class BookmarkUriUserKey(uriId: Id[NormalizedURI], userId: Id[User]) extends Key[Bookmark] {
  override val version = 2
  val namespace = "bookmark_uri_user"
  def toKey(): String = uriId.id + "#" + userId.id
}

class BookmarkUriUserCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[BookmarkUriUserKey, Bookmark](innermostPluginSettings, innerToOuterPluginSettings:_*)

object BookmarkStates extends States[Bookmark]

case class BookmarkSource(value: String) {
  implicit def getValue = value
  implicit def source(value: String) = BookmarkSource(value)
  override def toString = value
}

object BookmarkSource {
  implicit def source(value: String) = BookmarkSource(value)
}