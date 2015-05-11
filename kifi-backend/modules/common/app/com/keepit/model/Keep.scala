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
import com.keepit.common.crypto.PublicId

case class Keep(
    id: Option[Id[Keep]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[Keep] = ExternalId(),
    title: Option[String] = None,
    uriId: Id[NormalizedURI],
    isPrimary: Boolean = true, // trick to let us have multiple inactive Keeps while keeping integrity constraints
    inDisjointLib: Boolean,
    urlId: Id[URL],
    url: String, // denormalized for efficiency
    visibility: LibraryVisibility, // denormalized from this keep’s library
    userId: Id[User],
    state: State[Keep] = KeepStates.ACTIVE,
    source: KeepSource,
    kifiInstallation: Option[ExternalId[KifiInstallation]] = None,
    seq: SequenceNumber[Keep] = SequenceNumber.ZERO,
    libraryId: Option[Id[Library]],
    keptAt: DateTime = currentDateTime,
    sourceAttributionId: Option[Id[KeepSourceAttribution]] = None,
    note: Option[String] = None,
    originalKeeperId: Option[Id[User]]) extends ModelWithExternalId[Keep] with ModelWithState[Keep] with ModelWithSeqNumber[Keep] {

  def sanitizeForDelete(): Keep = copy(title = None, state = KeepStates.INACTIVE, kifiInstallation = None)

  def clean(): Keep = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  // todo(andrew): deprecate this field (right now, it just produces too many warnings to be of use)
  //@deprecated("Use `visibility` instead", "2014-08-25")
  def isPrivate = Keep.visibilityToIsPrivate(visibility)

  def withId(id: Id[Keep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

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

  @deprecated("Use `visibility` instead", "2014-08-29")
  def isDiscoverable = !isPrivate

}

object Keep {

  // If you see this after library migration is done, tell Andrew to clean up his messes.
  def isPrivateToVisibility(isPrivate: Boolean) = {
    if (isPrivate) {
      LibraryVisibility.SECRET
    } else {
      LibraryVisibility.DISCOVERABLE // This is not always true (post migration)! Do not use this!
    }
  }

  private def visibilityToIsPrivate(visibility: LibraryVisibility) = {
    visibility match {
      case LibraryVisibility.PUBLISHED | LibraryVisibility.DISCOVERABLE => false
      case LibraryVisibility.SECRET => true
    }
  }

  // is_primary: trueOrNull in db
  def applyFromDbRow(id: Option[Id[Keep]], createdAt: DateTime, updatedAt: DateTime, externalId: ExternalId[Keep],
    title: Option[String], uriId: Id[NormalizedURI], isPrimary: Option[Boolean], inDisjointLib: Option[Boolean],
    urlId: Id[URL], url: String, isPrivate: Boolean, userId: Id[User],
    state: State[Keep], source: KeepSource, kifiInstallation: Option[ExternalId[KifiInstallation]],
    seq: SequenceNumber[Keep], libraryId: Option[Id[Library]], visibility: LibraryVisibility, keptAt: DateTime,
    sourceAttributionId: Option[Id[KeepSourceAttribution]], note: Option[String], originalKeeperId: Option[Id[User]]) = {
    Keep(id, createdAt, updatedAt, externalId, title, uriId, isPrimary.exists(b => b), inDisjointLib.exists(b => b), urlId, url,
      visibility, userId, state, source, kifiInstallation, seq, libraryId, keptAt, sourceAttributionId, note, originalKeeperId)
  }
  def unapplyToDbRow(k: Keep) = {
    Some((k.id, k.createdAt, k.updatedAt, k.externalId, k.title, k.uriId, if (k.isPrimary) Some(true) else None,
      if (k.inDisjointLib) Some(true) else None, k.urlId, k.url, Keep.visibilityToIsPrivate(k.visibility),
      k.userId, k.state, k.source, k.kifiInstallation, k.seq, k.libraryId, k.visibility, k.keptAt, k.sourceAttributionId, k.note, k.originalKeeperId))
  }

  def _bookmarkFormat = (
    (__ \ 'id).formatNullable(Id.format[Keep]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[Keep]) and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'isPrimary).format[Boolean] and
    (__ \ 'inDisjointLib).format[Boolean] and
    (__ \ 'urlId).format(Id.format[URL]) and
    (__ \ 'url).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'state).format(State.format[Keep]) and
    (__ \ 'source).format[String].inmap(KeepSource.apply, unlift(KeepSource.unapply)) and
    (__ \ 'kifiInstallation).formatNullable(ExternalId.format[KifiInstallation]) and
    (__ \ 'seq).format(SequenceNumber.format[Keep]) and
    (__ \ 'libraryId).formatNullable(Id.format[Library]) and
    (__ \ 'keptAt).format(DateTimeJsonFormat) and
    (__ \ 'sourceAttributionId).formatNullable(Id.format[KeepSourceAttribution]) and
    (__ \ 'note).formatNullable[String] and
    (__ \ 'originalKeeperId).formatNullable[Id[User]]
  )(Keep.apply, unlift(Keep.unapply))

  // Remove when all services use the new Keep object
  implicit def bookmarkFormat = new Format[Keep] {
    def reads(j: JsValue) = {
      _bookmarkFormat.reads(j)
    }
    def writes(k: Keep) = {
      Json.obj(
        "id" -> k.id,
        "createdAt" -> k.createdAt,
        "updatedAt" -> k.updatedAt,
        "externalId" -> k.externalId,
        "title" -> k.title,
        "uriId" -> k.uriId,
        "isPrimary" -> k.isPrimary,
        "inDisjointLib" -> k.inDisjointLib,
        "urlId" -> k.urlId,
        "url" -> k.url,
        "bookmarkPath" -> (None: Option[String]),
        "visibility" -> k.visibility,
        "isPrivate" -> Keep.visibilityToIsPrivate(k.visibility),
        "userId" -> k.userId,
        "state" -> k.state,
        "source" -> k.source.value,
        "kifiInstallation" -> k.kifiInstallation,
        "seq" -> k.seq,
        "libraryId" -> k.libraryId,
        "keptAt" -> k.keptAt,
        "sourceAttributionId" -> k.sourceAttributionId,
        "note" -> k.note,
        "originalKeeperId" -> k.originalKeeperId
      )
    }
  }
}

case class KeepUriAndTime(uriId: Id[NormalizedURI], createdAt: DateTime = currentDateTime)

object KeepUriAndTime {
  import com.keepit.common.time.internalTime.DateTimeJsonLongFormat

  implicit def bookmarkUriAndTimeFormat = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'createdAt).format(DateTimeJsonLongFormat)
  )(KeepUriAndTime.apply, unlift(KeepUriAndTime.unapply))
}

case class KeepCountKey(userId: Id[User]) extends Key[Int] {
  override val version = 4
  val namespace = "bookmark_count"
  def toKey(): String = userId.toString
}

class KeepCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[KeepCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class GlobalKeepCountKey() extends Key[Int] {
  override val version = 1
  val namespace = "global_keeps_count"
  def toKey(): String = ""
}

class GlobalKeepCountCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[GlobalKeepCountKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class KeepUriUserKey(uriId: Id[NormalizedURI], userId: Id[User]) extends Key[Keep] {
  override val version = 10
  val namespace = "bookmark_uri_user"
  def toKey(): String = uriId.id + "#" + userId.id
}

class KeepUriUserCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[KeepUriUserKey, Keep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class CountByLibraryKey(id: Id[Library]) extends Key[Int] {
  override val version = 1
  val namespace = "count_by_lib"
  def toKey(): String = id.toString
}

class CountByLibraryCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[CountByLibraryKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

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
  val tagImport = KeepSource("tagImport")
  val emailReco = KeepSource("emailReco")
  val userCopied = KeepSource("userCopied")
  val twitterFileImport = KeepSource("twitterFileImport")
  val twitterSync = KeepSource("twitterSync")

  val valid = Set(keeper, bookmarkImport, site, mobile, email, default, bookmarkFileImport, kippt, pocket, instapaper, emailReco, twitterFileImport)

  val imports = Set(bookmarkImport, kippt, pocket, instapaper, bookmarkFileImport, twitterFileImport)

  // Sources that are from users uploading files, bulk actions, inputting URLs, etc.
  // These may be old links
  val bulk = Set(site, bookmarkImport, kippt, pocket, instapaper, bookmarkFileImport, twitterFileImport, userCopied, unknown)

  val discrete = Set(keeper, site, mobile, email, twitterSync)

  def get(value: String): KeepSource = KeepSource(value) match {
    case KeepSource("HOVER_KEEP") => keeper
    case KeepSource("INIT_LOAD") => bookmarkImport
    case source => source
  }
}

case class KeepAndTags(keep: Keep, tags: Set[Hashtag])

object KeepAndTags {
  implicit val format = Json.format[KeepAndTags]
}

case class BasicKeep(
  id: ExternalId[Keep],
  mine: Boolean,
  removable: Boolean,
  visibility: LibraryVisibility,
  libraryId: PublicId[Library])

object BasicKeep {
  implicit val format: Format[BasicKeep] = (
    (__ \ 'id).format[ExternalId[Keep]] and
    (__ \ 'mine).format[Boolean] and
    (__ \ 'removable).format[Boolean] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'libraryId).format[PublicId[Library]]
  )(BasicKeep.apply, unlift(BasicKeep.unapply))
}
