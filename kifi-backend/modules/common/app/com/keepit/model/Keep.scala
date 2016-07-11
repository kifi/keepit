package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache._
import com.keepit.common.crypto.{ ModelWithPublicId, PublicId, PublicIdConfiguration, PublicIdGenerator }
import com.keepit.common.db._
import com.keepit.common.json.{ SchemaReads, EnumFormat, TraversableFormat }
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.UserAgent
import com.keepit.common.path.Path
import com.keepit.common.core.traversableOnceExtensionOps
import com.keepit.common.reflection.Enumerator
import com.keepit.common.strings.StringWithNoLineBreaks
import com.keepit.common.time._
import com.keepit.discussion.{ MessageSource, Message }
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.data.validation.ValidationError
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
  url: String,
  userId: Option[Id[User]], // userId is None iff the message was imported from a foreign source (Slack, etc) and we don't have a Kifi user to attribute it to
  originalKeeperId: Option[Id[User]],
  source: KeepSource,
  keptAt: DateTime = currentDateTime,
  lastActivityAt: DateTime = currentDateTime, // denormalized to KeepToUser and KeepToLibrary, modify using KeepCommander.updateLastActivityAtifLater
  messageSeq: Option[SequenceNumber[Message]] = None,
  recipients: KeepRecipients)
    extends ModelWithExternalId[Keep] with ModelWithPublicId[Keep] with ModelWithState[Keep] with ModelWithSeqNumber[Keep] {

  def sanitizeForDelete: Keep = copy(title = None, note = None, state = KeepStates.INACTIVE, recipients = KeepRecipients.EMPTY)

  def clean: Keep = copy(title = title.map(_.trimAndRemoveLineBreaks), note = note.map(_.trimAndRemoveLineBreaks))

  def withId(id: Id[Keep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def withState(state: State[Keep]) = copy(state = state)
  def withUriId(normUriId: Id[NormalizedURI]) = copy(uriId = normUriId)

  def withOwner(newOwner: Id[User]) = this.copy(userId = Some(newOwner))
  def withTitle(title: Option[String]) = copy(title = title.map(_.trimAndRemoveLineBreaks).filter(title => title.nonEmpty && title != url))
  def withNote(newNote: Option[String]) = this.copy(note = newNote.filter(_.nonEmpty))

  def withRecipients(newRecipients: KeepRecipients): Keep = this.copy(recipients = newRecipients)
  def withLibraries(libraries: Set[Id[Library]]): Keep = this.withRecipients(recipients.withLibraries(libraries))
  def withParticipants(users: Set[Id[User]]): Keep = this.withRecipients(recipients.withUsers(users))

  // denormalized to KeepToUser and KeepToLibrary, use in KeepCommander.updateLastActivityAtifLater
  def withLastActivityAtIfLater(time: DateTime): Keep = if (lastActivityAt isBefore time) this.copy(lastActivityAt = time) else this

  def withMessageSeq(seq: SequenceNumber[Message]): Keep = if (messageSeq.exists(_ >= seq)) this else this.copy(messageSeq = Some(seq))

  def isActive: Boolean = state == KeepStates.ACTIVE
  def isInactive: Boolean = state == KeepStates.INACTIVE

  def isOlderThan(other: Keep): Boolean = keptAt < other.keptAt || (keptAt == other.keptAt && id.get.id < other.id.get.id)

  def titlePathString: String = {
    Stream(this.title, Some(this.url))
      .flatMap(_.map(_.trim.replaceAll("^https?://", "").replaceAll("[^A-Za-z0-9]", " ").replaceAll("  *", "-").replaceAll("^-|-$", "").take(40)))
      .headOption.getOrElse("keep")
  }

  def path(implicit config: PublicIdConfiguration) = Path(s"k/$titlePathString/${Keep.publicId(this.id.get).id}")

  // Only for use in old code which expects keeps to have a single library
  def lowestLibraryId: Option[Id[Library]] = recipients.libraries.minByOpt(_.id)
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
    (__ \ 'connections).format[KeepRecipients]
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
  override val version = 18
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
  override val version = 10
  val namespace = "keep_by_id"
  def toKey(): String = id.id.toString
}
class KeepByIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[KeepIdKey, Keep](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object KeepStates extends States[Keep]

abstract class KeepSource(val value: String) {
  override def toString = value
}
object KeepSource extends Enumerator[KeepSource] {

  case object Chrome extends KeepSource("Chrome")
  case object Firefox extends KeepSource("Firefox")
  case object Safari extends KeepSource("Safari")
  case object IPhone extends KeepSource("iPhone")
  case object Android extends KeepSource("Android")
  case object Slack extends KeepSource("Slack")

  // deprecated, use a more specific source such as the ext's browser name or mobile platform
  case object Keeper extends KeepSource("keeper")
  case object Mobile extends KeepSource("mobile")
  case object Discussion extends KeepSource("discussion")

  case object BookmarkImport extends KeepSource("bookmarkImport")
  case object BookmarkFileImport extends KeepSource("bookmarkFileImport")
  case object Site extends KeepSource("site")
  case object Email extends KeepSource("email")
  case object Default extends KeepSource("default")
  case object Unknown extends KeepSource("unknown")
  case object Kippt extends KeepSource("Kippt")
  case object Pocket extends KeepSource("Pocket")
  case object Instapaper extends KeepSource("Instapaper")
  case object Evernote extends KeepSource("Evernote")
  case object Diigo extends KeepSource("Diigo")
  case object TagImport extends KeepSource("tagImport")
  case object EmailReco extends KeepSource("emailReco")
  case object UserCopied extends KeepSource("userCopied")
  case object SystemCopied extends KeepSource("systemCopied")
  case object TwitterFileImport extends KeepSource("twitterFileImport")
  case object TwitterSync extends KeepSource("twitterSync")
  case object Fake extends KeepSource("fake")

  val all = _all
  def fromStr(str: String) = all.find(_.value.toLowerCase == str.toLowerCase)

  // will parse Kifi.com and browser ext UserAgents as "Chrome", so use KeepSource.site for more accuracy
  def fromUserAgent(agent: UserAgent): Option[KeepSource] = {
    if (agent.isKifiAndroidApp) Some(Android)
    else if (agent.isKifiIphoneApp) Some(IPhone)
    else fromStr(agent.name)
  }

  def fromMessageSource(msgSrc: MessageSource): Option[KeepSource] = msgSrc match {
    case MessageSource.SITE => Some(Site)
    case source => fromStr(source.value)
  }

  val imports: Set[KeepSource] = Set(BookmarkImport, Kippt, Pocket, Instapaper, Evernote, Diigo, BookmarkFileImport, TwitterFileImport, Slack)

  // Sources that are from users uploading files, bulk actions, inputting URLs, etc.
  // These may be old links
  val bulk: Set[KeepSource] = imports ++ Set(UserCopied, Unknown, Discussion)

  // One-at-a-time keeps
  val discrete: Set[KeepSource] = Set(Keeper, Site, Mobile, Email)

  val manual: Set[KeepSource] = Set(Keeper, Site, Mobile, Email)

  implicit val format: Format[KeepSource] = Format(
    Reads { j => j.validate[String].flatMap(str => KeepSource.fromStr(str).map(JsSuccess(_)).getOrElse(JsError(s"Invalid KeepSource: $str"))) },
    Writes { o => JsString(o.value) }
  )
  implicit val schemaReads: SchemaReads[KeepSource] = SchemaReads.trivial("keep_source")
}

case class KeepAndTags(keep: Keep, deprecated: (LibraryVisibility, Option[Id[Organization]]), source: Option[SourceAttribution], tags: Set[Hashtag])

object KeepAndTags {
  case class Helper(keep: Keep, source: Option[SourceAttribution], tags: Set[Hashtag])
  implicit val sourceFormat = SourceAttribution.internalFormat
  val helperFormat = Json.format[Helper]
  implicit val format: Format[KeepAndTags] = Format(
    Reads { j => helperFormat.reads(j).map(h => KeepAndTags(h.keep, (LibraryVisibility.SECRET, Option.empty), h.source, h.tags)) },
    Writes { kts =>
      val obj = helperFormat.writes(Helper(kts.keep, kts.source, kts.tags)).as[JsObject]
      obj deepMerge Json.obj("keep" -> Json.obj("visibility" -> kts.deprecated._1, "organizationId" -> kts.deprecated._2))
    }
  )
}

case class CrossServiceKeepAndTags(keep: CrossServiceKeep, source: Option[SourceAttribution], tags: Set[Hashtag])

object CrossServiceKeepAndTags {
  implicit val sourceFormat = SourceAttribution.internalFormat
  implicit val format = Json.format[CrossServiceKeepAndTags]
}

case class CrossServiceKeepRecipients(id: Id[Keep], owner: Option[Id[User]], recipients: KeepRecipients)
object CrossServiceKeepRecipients {
  implicit val format = Json.format[CrossServiceKeepRecipients]
  def fromKeep(keep: Keep): CrossServiceKeepRecipients = CrossServiceKeepRecipients(keep.id.get, keep.userId, keep.recipients)
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
    externalId: ExternalId[Keep],
    state: State[Keep],
    seq: SequenceNumber[Keep],
    owner: Option[Id[User]], // the person who "owns" the keep, if any
    users: Set[Id[User]], // all the users directly connected to the keep
    emails: Set[EmailAddress], // all the emails directly connected to the keep
    libraries: Set[CrossServiceKeep.LibraryInfo], // all the libraries directly connected to the keep
    url: String,
    uriId: Id[NormalizedURI],
    keptAt: DateTime,
    lastActivityAt: DateTime,
    title: Option[String],
    note: Option[String]) {
  def isActive: Boolean = state == KeepStates.ACTIVE
}
object CrossServiceKeep {
  case class LibraryInfo(id: Id[Library], visibility: LibraryVisibility, organizationId: Option[Id[Organization]], addedBy: Option[Id[User]])
  object LibraryInfo {
    def fromKTL(ktl: KeepToLibrary) = LibraryInfo(ktl.libraryId, ktl.visibility, ktl.organizationId, ktl.addedBy)
  }
  private implicit val libraryFormat = Json.format[LibraryInfo]
  implicit val format: Format[CrossServiceKeep] = (
    (__ \ 'id).format[Id[Keep]] and
    (__ \ 'externalId).format[ExternalId[Keep]] and
    (__ \ 'state).format[State[Keep]] and
    (__ \ 'seq).format[SequenceNumber[Keep]] and
    (__ \ 'owner).formatNullable[Id[User]] and
    (__ \ 'users).format[Set[Id[User]]] and
    (__ \ 'emails).format[Set[EmailAddress]] and
    (__ \ 'libraries).format[Set[CrossServiceKeep.LibraryInfo]] and
    (__ \ 'url).format[String] and
    (__ \ 'uriId).format[Id[NormalizedURI]] and
    (__ \ 'keptAt).format[DateTime] and
    (__ \ 'lastActivityAt).format[DateTime] and
    (__ \ 'title).formatNullable[String] and
    (__ \ 'note).formatNullable[String]
  )(CrossServiceKeep.apply, unlift(CrossServiceKeep.unapply))

  def fromKeepAndRecipients(keep: Keep, users: Set[Id[User]], emails: Set[EmailAddress], libraries: Set[LibraryInfo]): CrossServiceKeep = {
    CrossServiceKeep(
      id = keep.id.get,
      externalId = keep.externalId,
      state = keep.state,
      seq = keep.seq,
      owner = keep.userId,
      users = users,
      emails = emails,
      libraries = libraries,
      url = keep.url,
      uriId = keep.uriId,
      keptAt = keep.keptAt,
      lastActivityAt = keep.lastActivityAt,
      title = keep.title,
      note = keep.note
    )
  }
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

sealed abstract class KeepPermission(val value: String)
object KeepPermission extends Enumerator[KeepPermission] {
  case object ADD_LIBRARIES extends KeepPermission("add_libraries")
  case object ADD_MESSAGE extends KeepPermission("add_message")
  case object ADD_PARTICIPANTS extends KeepPermission("add_participants")
  case object DELETE_KEEP extends KeepPermission("delete_keep")
  case object DELETE_OWN_MESSAGES extends KeepPermission("delete_own_messages")
  case object DELETE_OTHER_MESSAGES extends KeepPermission("delete_other_messages")
  case object EDIT_KEEP extends KeepPermission("edit_keep")
  case object VIEW_KEEP extends KeepPermission("view_keep")
  case object REMOVE_LIBRARIES extends KeepPermission("remove_libraries")
  case object REMOVE_PARTICIPANTS extends KeepPermission("remove_participants")

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
  case object INVALID_KEEP_ID extends KeepFail(BAD_REQUEST, "invalid_keep_id")
  case object LIMIT_TOO_LARGE extends KeepFail(BAD_REQUEST, "limit_too_large")
  case object KEEP_NOT_FOUND extends KeepFail(NOT_FOUND, "no_keep_found")
  case object INSUFFICIENT_PERMISSIONS extends KeepFail(FORBIDDEN, "insufficient_permissions")
  case object MALFORMED_URL extends KeepFail(BAD_REQUEST, "malformed_url")
  case object COULD_NOT_PARSE extends KeepFail(BAD_REQUEST, "could_not_parse")
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
