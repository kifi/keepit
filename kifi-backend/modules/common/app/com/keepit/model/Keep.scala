package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.discussion.Message
import org.apache.commons.lang3.RandomStringUtils
import play.api.http.Status._
import play.api.mvc.PathBindable
import play.api.mvc.Results._

import scala.concurrent.duration._
import org.joda.time.DateTime
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicIdGenerator, ModelWithPublicId, PublicId }

import scala.util.Try
import scala.util.control.NoStackTrace
import scala.util.hashing.MurmurHash3

case class Keep(
  id: Option[Id[Keep]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Keep] = ExternalId(),
  title: Option[String] = None,
  uriId: Id[NormalizedURI],
  url: String, // denormalized for efficiency
  visibility: LibraryVisibility, // denormalized from this keep’s library
  userId: Id[User],
  state: State[Keep] = KeepStates.ACTIVE,
  source: KeepSource,
  seq: SequenceNumber[Keep] = SequenceNumber.ZERO,
  libraryId: Option[Id[Library]],
  keptAt: DateTime = currentDateTime,
  note: Option[String] = None,
  originalKeeperId: Option[Id[User]] = None,
  organizationId: Option[Id[Organization]] = None,
  connections: KeepConnections,
  messageSeq: Option[SequenceNumber[Message]] = None)
    extends ModelWithExternalId[Keep] with ModelWithPublicId[Keep] with ModelWithState[Keep] with ModelWithSeqNumber[Keep] {

  def sanitizeForDelete: Keep = copy(title = None, note = None, state = KeepStates.INACTIVE, connections = KeepConnections.EMPTY)

  def clean(): Keep = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  // todo(andrew): deprecate this field (right now, it just produces too many warnings to be of use)
  //@deprecated("Use `visibility` instead", "2014-08-25")
  def isPrivate = Keep.visibilityToIsPrivate(visibility)

  def withId(id: Id[Keep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withNote(newNote: Option[String]) = this.copy(note = newNote)

  def withState(state: State[Keep]) = copy(state = state)
  def withUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withTitle(title: Option[String]) = copy(title = title.map(_.trimAndRemoveLineBreaks()).filter(title => title.nonEmpty && title != url))

  def withLibrary(lib: Library) = this.copy(
    libraryId = Some(lib.id.get),
    visibility = lib.visibility,
    organizationId = lib.organizationId,
    connections = connections.withLibraries(Set(lib.id.get))
  )

  def withLibraries(libraries: Set[Id[Library]]): Keep = this.copy(connections = connections.withLibraries(libraries))
  def withParticipants(users: Set[Id[User]]): Keep = this.copy(connections = connections.withUsers(users))
  def withMessageSeq(seq: SequenceNumber[Message]): Keep = if (messageSeq.exists(_ >= seq)) this else this.copy(messageSeq = Some(seq))

  def isActive: Boolean = state == KeepStates.ACTIVE
  def isInactive: Boolean = state == KeepStates.INACTIVE

  def isOlderThan(other: Keep): Boolean = keptAt < other.keptAt || (keptAt == other.keptAt && id.get.id < other.id.get.id)

  def hasStrictlyLessValuableMetadataThan(other: Keep): Boolean = {
    this.isOlderThan(other) && (true || // TODO(ryan): remove this "(true ||" once we no longer want to mindlessly murder keeps
      Seq(
        note.isEmpty || note == other.note
      ).forall(b => b))
  }

  def titlePathString = this.title.getOrElse(this.url).trim.replaceAll("^https?://", "").replaceAll("[^A-Za-z0-9]", " ").replaceAll("  *", "-").replaceAll("^-|-$", "").take(40)

  def path(implicit config: PublicIdConfiguration) = Path(s"k/$titlePathString/${Keep.publicId(this.id.get).id}")
}

object Keep extends PublicIdGenerator[Keep] {

  protected[this] val publicIdPrefix = "k"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-28, 113, 122, 123, -126, 62, -12, 87, -112, 68, -9, -84, -56, -13, 15, 28))

  private def visibilityToIsPrivate(visibility: LibraryVisibility) = {
    visibility match {
      case LibraryVisibility.PUBLISHED | LibraryVisibility.DISCOVERABLE => false
      case LibraryVisibility.ORGANIZATION | LibraryVisibility.SECRET => true
    }
  }
  def fromDbRow(id: Option[Id[Keep]], createdAt: DateTime, updatedAt: DateTime, externalId: ExternalId[Keep],
    title: Option[String], uriId: Id[NormalizedURI], isPrimary: Option[Boolean],
    url: String, userId: Id[User],
    state: State[Keep], source: KeepSource,
    seq: SequenceNumber[Keep], libraryId: Option[Id[Library]], visibility: LibraryVisibility, keptAt: DateTime,
    note: Option[String], originalKeeperId: Option[Id[User]], organizationId: Option[Id[Organization]],
    connections: Option[KeepConnections], lh: LibrariesHash, ph: ParticipantsHash, messageSeq: Option[SequenceNumber[Message]]): Keep = {
    Keep(id, createdAt, updatedAt, externalId, title, uriId, url,
      visibility, userId, state, source, seq, libraryId, keptAt, note, originalKeeperId.orElse(Some(userId)),
      organizationId, connections.getOrElse(KeepConnections(libraryId.toSet, Set(userId))), messageSeq)
  }

  def toDbRow(k: Keep) = {
    Some(
      (k.id, k.createdAt, k.updatedAt, k.externalId, k.title,
        k.uriId, if (k.isActive) Some(true) else None, k.url,
        k.userId, k.state, k.source,
        k.seq, k.libraryId, k.visibility, k.keptAt,
        k.note, k.originalKeeperId.orElse(Some(k.userId)), k.organizationId,
        Some(k.connections), k.connections.librariesHash, k.connections.participantsHash, k.messageSeq)
    )
  }

  implicit val format: Format[Keep] = (
    (__ \ 'id).formatNullable[Id[Keep]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'externalId).format[ExternalId[Keep]] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'url).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'state).format[State[Keep]] and
    (__ \ 'source).format[KeepSource] and
    (__ \ 'seq).format[SequenceNumber[Keep]] and
    (__ \ 'libraryId).formatNullable[Id[Library]] and
    (__ \ 'keptAt).format[DateTime] and
    (__ \ 'note).formatNullable[String] and
    (__ \ 'originalKeeperId).formatNullable[Id[User]] and
    (__ \ 'organizationId).formatNullable[Id[Organization]] and
    (__ \ 'connections).format[KeepConnections] and
    (__ \ 'messageSeq).formatNullable[SequenceNumber[Message]]
  )(Keep.apply, unlift(Keep.unapply))
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
  override val version = 13
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
  override val version = 4
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
  val slack = KeepSource("slack")
  val discussion = KeepSource("discussion")

  val imports = Set(bookmarkImport, kippt, pocket, instapaper, evernote, diigo, bookmarkFileImport, twitterFileImport)

  // Sources that are from users uploading files, bulk actions, inputting URLs, etc.
  // These may be old links
  val bulk = imports ++ Set(userCopied, unknown, discussion)

  // One-at-a-time keeps
  val discrete = Set(keeper, site, mobile, email, twitterSync, slack)

  val manual = Set(keeper, site, mobile, email)

  def get(value: String): KeepSource = KeepSource(value) match {
    case KeepSource("HOVER_KEEP") => keeper
    case KeepSource("INIT_LOAD") => bookmarkImport
    case source => source
  }

  implicit val format: Format[KeepSource] = Format(
    Reads { j => j.validate[String].map(KeepSource(_)) },
    Writes { o => JsString(o.value) }
  )
}

case class KeepAndTags(keep: Keep, source: Option[SourceAttribution], tags: Set[Hashtag])

object KeepAndTags {
  implicit val sourceFormat = SourceAttribution.internalFormat
  implicit val format = Json.format[KeepAndTags]
}

case class BasicKeep(
  id: ExternalId[Keep],
  title: Option[String],
  url: String,
  visibility: LibraryVisibility,
  libraryId: Option[PublicId[Library]],
  ownerId: ExternalId[User])

object BasicKeep {
  implicit val format: Format[BasicKeep] = (
    (__ \ 'id).format[ExternalId[Keep]] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'libraryId).formatNullable[PublicId[Library]] and
    (__ \ 'ownerId).format[ExternalId[User]]
  )(BasicKeep.apply, unlift(BasicKeep.unapply))
}

// All the important parts of a Keep to send across services
// NOT to be sent to clients
// PSA: Think of a Keep as the source node of a graph
// People can view the keep if they are connected to the Keep node:
//     1. Directly (via a keep-to-user)
//     2. Indirectly, via a library (keep -> library -> library-membership -> user)
//     3. Indirectly, via an organization (keep -> library -> organization -> organization-membership -> user)
case class CrossServiceKeep(
  id: Id[Keep],
  owner: Id[User], // the person who created the keep
  users: Set[Id[User]], // all the users directly connected to the keep
  libraries: Set[Id[Library]], // all the libraries directly connected to the keep
  url: String,
  uriId: Id[NormalizedURI],
  keptAt: DateTime,
  title: Option[String],
  note: Option[String])
object CrossServiceKeep {
  implicit val format: Format[CrossServiceKeep] = (
    (__ \ 'id).format[Id[Keep]] and
    (__ \ 'owner).format[Id[User]] and
    (__ \ 'users).format[Set[Id[User]]] and
    (__ \ 'libraries).format[Set[Id[Library]]] and
    (__ \ 'url).format[String] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'keptAt).format[DateTime] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'note).formatNullable[String]
  )(CrossServiceKeep.apply, unlift(CrossServiceKeep.unapply))
}

case class PersonalKeep(
  id: ExternalId[Keep],
  mine: Boolean,
  removable: Boolean,
  visibility: LibraryVisibility,
  libraryId: Option[PublicId[Library]])

object PersonalKeep {
  implicit val format: Format[PersonalKeep] = (
    (__ \ 'id).format[ExternalId[Keep]] and
    (__ \ 'mine).format[Boolean] and
    (__ \ 'removable).format[Boolean] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'libraryId).formatNullable[PublicId[Library]]
  )(PersonalKeep.apply, unlift(PersonalKeep.unapply))
}

case class BasicKeepIdKey(id: Id[Keep]) extends Key[BasicKeep] {
  override val version = 1
  val namespace = "basic_keep_by_id"
  def toKey(): String = id.id.toString
}

class BasicKeepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicKeepIdKey, BasicKeep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

sealed abstract class KeepPermission(val value: String)
object KeepPermission extends Enumerator[KeepPermission] {
  case object VIEW_KEEP extends KeepPermission("view_keep")
  case object ADD_MESSAGE extends KeepPermission("add_message")
  case object DELETE_OWN_MESSAGES extends KeepPermission("delete_own_messages")
  case object DELETE_OTHER_MESSAGES extends KeepPermission("delete_other_messages")
  case object DELETE_KEEP extends KeepPermission("delete_keep")
  case object VIEW_MESSAGES extends KeepPermission("view_messages")
}

sealed abstract class KeepFail(val status: Int, val err: String) extends Exception(err) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> err))
}

object KeepFail extends Enumerator[KeepFail] {
  case object INVALID_ID extends KeepFail(BAD_REQUEST, "invalid_keep_id")
  case object KEEP_NOT_FOUND extends KeepFail(NOT_FOUND, "no_keep_found")
  case object INSUFFICIENT_PERMISSIONS extends KeepFail(FORBIDDEN, "insufficient_permissions")
}
