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

import scala.util.hashing.MurmurHash3

case class Keep(
    id: Option[Id[Keep]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[Keep] = ExternalId(),
    title: Option[String] = None,
    uriId: Id[NormalizedURI],
    isPrimary: Boolean = true, // trick to let us have multiple inactive Keeps while keeping integrity constraints
    url: String, // denormalized for efficiency
    visibility: LibraryVisibility, // denormalized from this keep’s library
    userId: Id[User],
    state: State[Keep] = KeepStates.ACTIVE,
    source: KeepSource,
    seq: SequenceNumber[Keep] = SequenceNumber.ZERO,
    libraryId: Option[Id[Library]],
    keptAt: DateTime = currentDateTime,
    sourceAttributionId: Option[Id[KeepSourceAttribution]] = None,
    note: Option[String] = None,
    originalKeeperId: Option[Id[User]] = None,
    organizationId: Option[Id[Organization]] = None,
    librariesHash: Option[LibrariesHash] = None,
    participantsHash: Option[ParticipantsHash] = None) extends ModelWithExternalId[Keep] with ModelWithState[Keep] with ModelWithSeqNumber[Keep] {

  def withPrimary(newPrimary: Boolean) = this.copy(isPrimary = newPrimary)
  def sanitizeForDelete: Keep = copy(title = None, state = KeepStates.INACTIVE, isPrimary = false, librariesHash = Some(LibrariesHash.EMPTY), participantsHash = Some(ParticipantsHash.EMPTY))

  def clean(): Keep = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  // todo(andrew): deprecate this field (right now, it just produces too many warnings to be of use)
  //@deprecated("Use `visibility` instead", "2014-08-25")
  def isPrivate = Keep.visibilityToIsPrivate(visibility)

  def withId(id: Id[Keep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withNote(newNote: Option[String]) = this.copy(note = newNote)
  def withVisibility(newVisibility: LibraryVisibility) = this.copy(visibility = newVisibility)
  def withOwner(newOwner: Id[User]) = this.copy(userId = newOwner)

  def withActive(isActive: Boolean) = copy(state = isActive match {
    case true => KeepStates.ACTIVE
    case false => KeepStates.INACTIVE
  })

  def withState(state: State[Keep]) = copy(state = state)

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withUrl(url: String) = copy(url = url)

  def withTitle(title: Option[String]) = copy(title = title.map(_.trimAndRemoveLineBreaks()).filter(title => title.nonEmpty && title != url))

  def withLibrary(lib: Library) = this.copy(
    libraryId = Some(lib.id.get),
    visibility = lib.visibility,
    organizationId = lib.organizationId
  )

  def withLibraries(libraries: Set[Id[Library]]): Keep = this.copy(librariesHash = Some(LibrariesHash(libraries)))
  def withParticipants(users: Set[Id[User]]): Keep = this.copy(participantsHash = Some(ParticipantsHash(users)))

  def isActive: Boolean = state == KeepStates.ACTIVE && isPrimary // isPrimary will be removed shortly
  def isInactive: Boolean = state == KeepStates.INACTIVE

  def isOlderThan(other: Keep): Boolean = keptAt < other.keptAt || (keptAt == other.keptAt && id.get.id < other.id.get.id)

  def hasStrictlyLessValuableMetadataThan(other: Keep): Boolean = {
    this.isOlderThan(other) && (true || // TODO(ryan): remove this "(true ||" once we no longer want to mindlessly murder keeps
      Seq(
        note.isEmpty || note == other.note
      ).forall(b => b))
  }
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
      case LibraryVisibility.ORGANIZATION | LibraryVisibility.SECRET => true
    }
  }

  def applyFromDbRowTuples(firstArguments: KeepFirstArguments, restArguments: KeepRestArguments): Keep = (firstArguments, restArguments) match {
    case ((id, createdAt, updatedAt, externalId, title, uriId, isPrimary, url),
      (isPrivate, userId, state, source, seq, libraryId, visibility, keptAt, sourceAttributionId, note, originalKeeperId, organizationId, librariesHash, participantsHash)) =>
      _applyFromDbRow(id, createdAt, updatedAt, externalId, title,
        uriId = uriId, isPrivate = isPrivate, isPrimary = isPrimary, url = url,
        userId = userId, state = state, source = source,
        seq = seq, libraryId = libraryId, visibility = visibility, keptAt = keptAt,
        sourceAttributionId = sourceAttributionId, note = note, originalKeeperId = originalKeeperId,
        organizationId = organizationId, librariesHash = librariesHash, participantsHash = participantsHash)
  }

  // is_primary: trueOrNull in db
  def _applyFromDbRow(id: Option[Id[Keep]], createdAt: DateTime, updatedAt: DateTime, externalId: ExternalId[Keep],
    title: Option[String], uriId: Id[NormalizedURI], isPrimary: Option[Boolean],
    url: String, isPrivate: Boolean, userId: Id[User],
    state: State[Keep], source: KeepSource,
    seq: SequenceNumber[Keep], libraryId: Option[Id[Library]], visibility: LibraryVisibility, keptAt: DateTime,
    sourceAttributionId: Option[Id[KeepSourceAttribution]], note: Option[String], originalKeeperId: Option[Id[User]], organizationId: Option[Id[Organization]], librariesHash: Option[LibrariesHash], participantsHash: Option[ParticipantsHash]): Keep = {
    Keep(id, createdAt, updatedAt, externalId, title, uriId, isPrimary.exists(b => b), url,
      visibility, userId, state, source, seq, libraryId, keptAt, sourceAttributionId, note, originalKeeperId.orElse(Some(userId)), organizationId, librariesHash, participantsHash)
  }

  def unapplyToDbRow(k: Keep) = {
    Some(
      (k.id, k.createdAt, k.updatedAt, k.externalId, k.title,
        k.uriId, if (k.isPrimary) Some(true) else None, k.url),
      (Keep.visibilityToIsPrivate(k.visibility), k.userId, k.state, k.source,
        k.seq, k.libraryId, k.visibility, k.keptAt, k.sourceAttributionId,
        k.note, k.originalKeeperId.orElse(Some(k.userId)), k.organizationId, k.librariesHash, k.participantsHash)
    )
  }

  private type KeepFirstArguments = (Option[Id[Keep]], DateTime, DateTime, ExternalId[Keep], Option[String], Id[NormalizedURI], Option[Boolean], String)
  private type KeepRestArguments = (Boolean, Id[User], State[Keep], KeepSource, SequenceNumber[Keep], Option[Id[Library]], LibraryVisibility, DateTime, Option[Id[KeepSourceAttribution]], Option[String], Option[Id[User]], Option[Id[Organization]], Option[LibrariesHash], Option[ParticipantsHash])
  def _bookmarkFormat = {
    val fields1To10: Reads[KeepFirstArguments] = (
      (__ \ 'id).readNullable(Id.format[Keep]) and
      (__ \ 'createdAt).read(DateTimeJsonFormat) and
      (__ \ 'updatedAt).read(DateTimeJsonFormat) and
      (__ \ 'externalId).read(ExternalId.format[Keep]) and
      (__ \ 'title).readNullable[String] and
      (__ \ 'uriId).read(Id.format[NormalizedURI]) and
      (__ \ 'isPrimary).readNullable[Boolean] and
      (__ \ 'url).read[String]).tupled
    val fields10Up: Reads[KeepRestArguments] = (
      (__ \ 'isPrivate).readNullable[Boolean].map(_.getOrElse(false)) and
      (__ \ 'userId).read(Id.format[User]) and
      (__ \ 'state).read(State.format[Keep]) and
      (__ \ 'source).read[String].map(KeepSource(_)) and
      (__ \ 'seq).read(SequenceNumber.format[Keep]) and
      (__ \ 'libraryId).readNullable(Id.format[Library]) and
      (__ \ 'visibility).read[LibraryVisibility] and
      (__ \ 'keptAt).read(DateTimeJsonFormat) and
      (__ \ 'sourceAttributionId).readNullable(Id.format[KeepSourceAttribution]) and
      (__ \ 'note).readNullable[String] and
      (__ \ 'originalKeeperId).readNullable[Id[User]] and
      (__ \ 'organizationId).readNullable[Id[Organization]] and
      (__ \ 'librariesHash).readNullable[LibrariesHash] and
      (__ \ 'participantsHash).readNullable[ParticipantsHash]
    ).tupled

    (fields1To10 and fields10Up).apply(applyFromDbRowTuples _)
  }

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
        "url" -> k.url,
        "bookmarkPath" -> (None: Option[String]),
        "visibility" -> k.visibility,
        "isPrivate" -> Keep.visibilityToIsPrivate(k.visibility),
        "userId" -> k.userId,
        "state" -> k.state,
        "source" -> k.source.value,
        "seq" -> k.seq,
        "libraryId" -> k.libraryId,
        "keptAt" -> k.keptAt,
        "sourceAttributionId" -> k.sourceAttributionId,
        "note" -> k.note,
        "originalKeeperId" -> k.originalKeeperId.orElse(Some(k.userId)),
        "organizationId" -> k.organizationId,
        "librariesHash" -> k.librariesHash,
        "participantsHash" -> k.participantsHash
      )
    }
  }
}

case class LibrariesHash(value: Int) extends AnyVal
object LibrariesHash {
  val EMPTY = LibrariesHash(Set.empty[Id[Library]])
  def apply(libraries: Set[Id[Library]]): LibrariesHash = LibrariesHash(MurmurHash3.setHash(libraries))
  implicit val format: Format[LibrariesHash] =
    Format(__.read[Int].map(LibrariesHash(_)), new Writes[LibrariesHash] {
      def writes(hash: LibrariesHash) = JsNumber(hash.value)
    })
}

case class ParticipantsHash(value: Int) extends AnyVal
object ParticipantsHash {
  val EMPTY = ParticipantsHash(Set.empty[Id[User]])
  def apply(users: Set[Id[User]]): ParticipantsHash = ParticipantsHash(MurmurHash3.setHash(users))
  implicit val format: Format[ParticipantsHash] =
    Format(__.read[Int].map(ParticipantsHash(_)), new Writes[ParticipantsHash] {
      def writes(hash: ParticipantsHash) = JsNumber(hash.value)
    })
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
  override val version = 11
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

case class KeepIdKey(id: Id[Keep]) extends Key[Keep] {
  override val version = 1
  val namespace = "keep_by_id"
  def toKey(): String = id.id.toString
}
class KeepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[KeepIdKey, Keep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object KeepStates extends States[Keep]

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
  val evernote = KeepSource("Evernote")
  val diigo = KeepSource("Diigo")
  val tagImport = KeepSource("tagImport")
  val emailReco = KeepSource("emailReco")
  val userCopied = KeepSource("userCopied")
  val systemCopied = KeepSource("systemCopied")
  val twitterFileImport = KeepSource("twitterFileImport")
  val twitterSync = KeepSource("twitterSync")

  val imports = Set(bookmarkImport, kippt, pocket, instapaper, evernote, diigo, bookmarkFileImport, twitterFileImport)

  // Sources that are from users uploading files, bulk actions, inputting URLs, etc.
  // These may be old links
  val bulk = imports ++ Set(userCopied, unknown)

  val discrete = Set(keeper, site, mobile, email, twitterSync)

  val manual = Set(keeper, site, mobile, email)

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
  title: Option[String],
  url: String,
  visibility: LibraryVisibility,
  libraryId: PublicId[Library],
  ownerId: ExternalId[User])

object BasicKeep {
  implicit val format: Format[BasicKeep] = (
    (__ \ 'id).format[ExternalId[Keep]] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'libraryId).format[PublicId[Library]] and
    (__ \ 'ownerId).format[ExternalId[User]]
  )(BasicKeep.apply, unlift(BasicKeep.unapply))
}

case class PersonalKeep(
  id: ExternalId[Keep],
  mine: Boolean,
  removable: Boolean,
  visibility: LibraryVisibility,
  libraryId: PublicId[Library])

object PersonalKeep {
  implicit val format: Format[PersonalKeep] = (
    (__ \ 'id).format[ExternalId[Keep]] and
    (__ \ 'mine).format[Boolean] and
    (__ \ 'removable).format[Boolean] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'libraryId).format[PublicId[Library]]
  )(PersonalKeep.apply, unlift(PersonalKeep.unapply))
}

case class BasicKeepIdKey(id: Id[Keep]) extends Key[BasicKeep] {
  override val version = 1
  val namespace = "basic_keep_by_id"
  def toKey(): String = id.id.toString
}

class BasicKeepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicKeepIdKey, BasicKeep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
