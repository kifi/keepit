package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache._
import com.keepit.common.crypto.{ ModelWithPublicId, PublicId, PublicIdConfiguration, PublicIdGenerator }
import com.keepit.common.db._
import com.keepit.common.json.{ EnumFormat, TraversableFormat, TupleFormat }
import com.keepit.common.logging.AccessLog
import com.keepit.common.path.Path
import com.keepit.common.reflection.Enumerator
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.social.{ BasicAuthor, BasicUser }
import org.joda.time.DateTime
import play.api.http.Status._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import play.api.mvc.Results._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

case class Keep(
  id: Option[Id[Keep]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[Keep] = KeepStates.ACTIVE,
  seq: SequenceNumber[Keep] = SequenceNumber.ZERO,
  externalId: ExternalId[Keep] = ExternalId(),
  title: Option[String] = None,
  note: Option[String] = None,
  uriId: Id[NormalizedURI],
  url: String, // denormalized for efficiency
  userId: Option[Id[User]], // userId is None iff the message was imported from a foreign source (Slack, etc) and we don't have a Kifi user to attribute it to
  originalKeeperId: Option[Id[User]],
  source: KeepSource,
  keptAt: DateTime = currentDateTime,
  lastActivityAt: DateTime = currentDateTime, // denormalized to KeepToUser and KeepToLibrary, modify using KeepCommander.updateLastActivityAtifLater
  messageSeq: Option[SequenceNumber[Message]] = None,
  connections: KeepConnections,
  libraryId: Option[Id[Library]], // deprecated, prefer connections.libraries
  visibility: LibraryVisibility, // deprecated, prefer KeepToLibrary.visibility
  organizationId: Option[Id[Organization]] = None)
    extends ModelWithExternalId[Keep] with ModelWithPublicId[Keep] with ModelWithState[Keep] with ModelWithSeqNumber[Keep] {

  def sanitizeForDelete: Keep = copy(title = None, note = None, state = KeepStates.INACTIVE, connections = KeepConnections.EMPTY)

  def clean(): Keep = copy(title = title.map(_.trimAndRemoveLineBreaks()))

  // todo(andrew): deprecate this field (right now, it just produces too many warnings to be of use)
  //@deprecated("Use `visibility` instead", "2014-08-25")
  def isPrivate = Keep.visibilityToIsPrivate(visibility)

  def withId(id: Id[Keep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withState(state: State[Keep]) = copy(state = state)
  def withUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withOwner(newOwner: Id[User]) = this.copy(userId = Some(newOwner))
  def withTitle(title: Option[String]) = copy(title = title.map(_.trimAndRemoveLineBreaks()).filter(title => title.nonEmpty && title != url))
  def withNote(newNote: Option[String]) = this.copy(note = newNote)

  def withLibrary(lib: Library) = this.copy(
    libraryId = Some(lib.id.get),
    visibility = lib.visibility,
    organizationId = lib.organizationId,
    connections = connections.withLibraries(Set(lib.id.get))
  )

  def withConnections(connections: KeepConnections): Keep = this.copy(connections = connections)
  def withLibraries(libraries: Set[Id[Library]]): Keep = this.copy(connections = connections.withLibraries(libraries))
  def withParticipants(users: Set[Id[User]]): Keep = this.copy(connections = connections.withUsers(users))

  // denormalized to KeepToUser and KeepToLibrary, use in KeepCommander.updateLastActivityAtifLater
  def withLastActivityAtIfLater(time: DateTime): Keep = if (lastActivityAt isBefore time) this.copy(lastActivityAt = time) else this

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
  implicit val format: Format[Keep] = (
    (__ \ 'id).formatNullable[Id[Keep]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'state).format[State[Keep]] and
    (__ \ 'seq).format[SequenceNumber[Keep]] and
    (__ \ 'externalId).format[ExternalId[Keep]] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'note).formatNullable[String] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'url).format[String] and
    (__ \ 'userId).formatNullable[Id[User]] and
    (__ \ 'originalKeeperId).formatNullable[Id[User]] and
    (__ \ 'source).format[KeepSource] and
    (__ \ 'keptAt).format[DateTime] and
    (__ \ 'lastActivityAt).format[DateTime] and
    (__ \ 'messageSeq).formatNullable[SequenceNumber[Message]] and
    (__ \ 'connections).format[KeepConnections] and
    (__ \ 'libraryId).formatNullable[Id[Library]] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'organizationId).formatNullable[Id[Organization]]
  )(Keep.apply, unlift(Keep.unapply))
}

case class KeepCountKey(userId: Id[User]) extends Key[Int] {
  override val version = 4
  val namespace = "bookmark_count"
  def toKey(): String = userId.id.toString
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
  override val version = 15
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
  override val version = 6
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

  val imports = Set(bookmarkImport, kippt, pocket, instapaper, evernote, diigo, bookmarkFileImport, twitterFileImport, slack)

  // Sources that are from users uploading files, bulk actions, inputting URLs, etc.
  // These may be old links
  val bulk = imports ++ Set(userCopied, unknown, discussion)

  // One-at-a-time keeps
  val discrete = Set(keeper, site, mobile, email, twitterSync)

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
  author: BasicAuthor,
  attribution: Option[SlackAttribution])

object BasicKeep {
  private def GARBAGE_UUID: ExternalId[User] = ExternalId("42424242-4242-4242-424242424242")
  implicit val format: Format[BasicKeep] = (
    (__ \ 'id).format[ExternalId[Keep]] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'url).format[String] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'libraryId).formatNullable[PublicId[Library]] and
    (__ \ 'author).format[BasicAuthor] and
    (__ \ 'slackAttribution).formatNullable[SlackAttribution]
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
  owner: Option[Id[User]], // the person who "owns" the keep, if any
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
    (__ \ 'owner).formatNullable[Id[User]] and
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
  override val version = 4
  val namespace = "basic_keep_by_id"
  def toKey(): String = id.id.toString
}

class BasicKeepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[BasicKeepIdKey, BasicKeep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

sealed abstract class KeepPermission(val value: String)
object KeepPermission extends Enumerator[KeepPermission] {
  case object ADD_MESSAGE extends KeepPermission("add_message")
  case object ADD_PARTICIPANTS extends KeepPermission("add_participants")
  case object DELETE_KEEP extends KeepPermission("delete_keep")
  case object DELETE_OWN_MESSAGES extends KeepPermission("delete_own_messages")
  case object DELETE_OTHER_MESSAGES extends KeepPermission("delete_other_messages")
  case object VIEW_KEEP extends KeepPermission("view_keep")
  case object EDIT_KEEP extends KeepPermission("edit_keep")

  def all: Set[KeepPermission] = _all.toSet

  val format: Format[KeepPermission] = Format(
    EnumFormat.reads(get, all.map(_.value)),
    Writes { o => JsString(o.value) }
  )

  implicit val writes = Writes(format.writes)
  val reads = Reads(format.reads)
  implicit val safeSetReads = TraversableFormat.safeSetReads[KeepPermission](reads)

  def get(str: String) = all.find(_.value == str)
  def apply(str: String): KeepPermission = get(str).getOrElse(throw new Exception(s"Unknown KeepPermission $str"))
}

sealed abstract class KeepFail(val status: Int, val err: String) extends Exception(err) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> err))
}

object KeepFail extends Enumerator[KeepFail] {
  case object INVALID_ID extends KeepFail(BAD_REQUEST, "invalid_keep_id")
  case object KEEP_NOT_FOUND extends KeepFail(NOT_FOUND, "no_keep_found")
  case object INSUFFICIENT_PERMISSIONS extends KeepFail(FORBIDDEN, "insufficient_permissions")
}

abstract class FeedFilter(val kind: String)
abstract class ShoeboxFeedFilter(kind: String) extends FeedFilter(kind)
abstract class ElizaFeedFilter(kind: String) extends FeedFilter(kind)
object FeedFilter {
  case object OwnKeeps extends ShoeboxFeedFilter("own")
  case class OrganizationKeeps(orgId: Id[Organization]) extends ShoeboxFeedFilter("org")
  case object Unread extends ElizaFeedFilter("unread")
  case object Sent extends ElizaFeedFilter("sent")
  case object All extends ElizaFeedFilter("all")

  def apply(kind: String, id: Option[String])(implicit publicIdConfig: PublicIdConfiguration): Option[FeedFilter] = kind match {
    case OwnKeeps.kind => Some(OwnKeeps)
    case Unread.kind => Some(Unread)
    case Sent.kind => Some(Sent)
    case All.kind => Some(All)
    case "org" => id.flatMap(Organization.decodePublicIdStr(_).toOption).map(OrganizationKeeps)
    case _ => None
  }

  def toElizaFilter(kind: String): Option[ElizaFeedFilter] = kind match {
    case Unread.kind => Some(Unread)
    case Sent.kind => Some(Sent)
    case All.kind => Some(All)
    case _ => None
  }

  def toShoeboxFilter(kind: String, id: Option[String])(implicit publicIdConfig: PublicIdConfiguration): Option[ShoeboxFeedFilter] = {
    kind match {
      case OwnKeeps.kind => Some(OwnKeeps)
      case "org" => id.flatMap(Organization.decodePublicIdStr(_).toOption).map(OrganizationKeeps)
      case _ => None
    }
  }

  implicit def elizaQueryStringBinder(implicit stringBinder: QueryStringBindable[String]): QueryStringBindable[ElizaFeedFilter] = new QueryStringBindable[ElizaFeedFilter] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ElizaFeedFilter]] = {
      stringBinder.bind(key, params) map {
        case Right(kind) => toElizaFilter(kind).toRight(left = "Unable to bind an ElizaFeedFilter")
        case _ => Left("Unable to bind an ElizaFeedFilter")
      }
    }

    override def unbind(key: String, filter: ElizaFeedFilter): String = {
      stringBinder.unbind(key, filter.kind)
    }
  }
}
