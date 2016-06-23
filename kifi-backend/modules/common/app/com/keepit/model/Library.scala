package com.keepit.model

import java.net.URLEncoder
import java.util.regex.Pattern
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration, ModelWithPublicId, PublicIdGenerator }
import com.keepit.common.db._
import com.keepit.common.json.EnumFormat
import com.keepit.common.logging.AccessLog
import com.keepit.common.path.{ EncodedPath, Path }
import com.keepit.common.reflection.Enumerator
import com.keepit.common.strings.UTF8
import com.keepit.common.time._
import com.keepit.social.BasicUser
import com.kifi.macros.json

import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.math3.random.MersenneTwister
import org.joda.time.DateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.concurrent.duration.Duration

import scala.util.Random
import scala.util.hashing.MurmurHash3

case class Library(
    id: Option[Id[Library]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: String,
    ownerId: Id[User],
    visibility: LibraryVisibility,
    description: Option[String] = None,
    slug: LibrarySlug,
    color: Option[LibraryColor] = None,
    state: State[Library] = LibraryStates.ACTIVE,
    seq: SequenceNumber[Library] = SequenceNumber.ZERO,
    kind: LibraryKind = LibraryKind.USER_CREATED,
    universalLink: String = RandomStringUtils.randomAlphanumeric(40),
    memberCount: Int,
    lastKept: Option[DateTime] = None,
    keepCount: Int = 0,
    whoCanInvite: Option[LibraryInvitePermissions] = None,
    organizationId: Option[Id[Organization]] = None,
    organizationMemberAccess: Option[LibraryAccess] = None,
    whoCanComment: LibraryCommentPermissions = LibraryCommentPermissions.ANYONE) extends ModelWithPublicId[Library] with ModelWithState[Library] with ModelWithSeqNumber[Library] {

  def sanitizeForDelete: Library = this.copy(
    name = RandomStringUtils.randomAlphanumeric(20),
    description = None,
    state = LibraryStates.INACTIVE,
    slug = LibrarySlug(RandomStringUtils.randomAlphanumeric(20)))

  def withId(id: Id[Library]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(myState: State[Library]) = this.copy(state = myState)
  def withName(newName: String) = this.copy(name = newName)
  def withOwner(newOwner: Id[User]) = this.copy(ownerId = newOwner)
  def withOpenCollab(access: Option[LibraryAccess]) = this.copy(organizationMemberAccess = access)
  val isPublished: Boolean = visibility == LibraryVisibility.PUBLISHED
  val isSecret: Boolean = visibility == LibraryVisibility.SECRET
  def canBeModified: Boolean = kind == LibraryKind.USER_CREATED || kind == LibraryKind.SLACK_CHANNEL
  def isSystemLibrary: Boolean = !canBeModified
  def canAnyoneComment: Boolean = whoCanComment == LibraryCommentPermissions.ANYONE

  def space: LibrarySpace = LibrarySpace(ownerId, organizationId)

  def isActive: Boolean = state == LibraryStates.ACTIVE

}

object Library extends PublicIdGenerator[Library] {

  val SYSTEM_MAIN_DISPLAY_NAME = "My Main Library"
  val SYSTEM_SECRET_DISPLAY_NAME = "My Private Library"

  def getDisplayName(name: String, kind: LibraryKind): String = kind match {
    case LibraryKind.SYSTEM_MAIN => SYSTEM_MAIN_DISPLAY_NAME
    case LibraryKind.SYSTEM_SECRET => SYSTEM_SECRET_DISPLAY_NAME
    case _ => name
  }

  def applyFromDbRow(
    id: Option[Id[Library]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[Library],
    name: String,
    ownerId: Id[User],
    description: Option[String],
    visibility: LibraryVisibility,
    slug: LibrarySlug,
    color: Option[LibraryColor],
    seq: SequenceNumber[Library],
    kind: LibraryKind,
    memberCount: Int,
    universalLink: String,
    lastKept: Option[DateTime],
    keepCount: Int,
    whoCanInvite: Option[LibraryInvitePermissions],
    organizationId: Option[Id[Organization]],
    organizationMemberAccess: Option[LibraryAccess],
    whoCanComment: LibraryCommentPermissions) = {
    Library(id, createdAt, updatedAt, getDisplayName(name, kind), ownerId, visibility, description, slug, color, state, seq, kind, universalLink, memberCount, lastKept, keepCount, whoCanInvite, organizationId, organizationMemberAccess, whoCanComment)
  }

  def unapplyToDbRow(lib: Library) = {
    Some(
      lib.id,
      lib.createdAt,
      lib.updatedAt,
      lib.state,
      lib.name,
      lib.ownerId,
      lib.description,
      lib.visibility,
      lib.slug,
      lib.color,
      lib.seq,
      lib.kind,
      lib.memberCount,
      lib.universalLink,
      lib.lastKept,
      lib.keepCount,
      lib.whoCanInvite,
      lib.organizationId,
      lib.organizationMemberAccess,
      lib.whoCanComment
    )
  }

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-72, -49, 51, -61, 42, 43, 123, -61, 64, 122, -121, -55, 117, -51, 12, 21))

  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Library]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[Library]) and
    (__ \ 'name).format[String] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'description).format[Option[String]] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'color).formatNullable[LibraryColor] and
    (__ \ 'seq).format(SequenceNumber.format[Library]) and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'memberCount).format[Int] and
    (__ \ 'universalLink).format[String] and
    (__ \ 'lastKept).formatNullable[DateTime] and
    (__ \ 'keepCount).format[Int] and
    (__ \ 'whoCanInvite).formatNullable[LibraryInvitePermissions] and
    (__ \ "orgId").formatNullable[Id[Organization]] and
    (__ \ "orgMemberAccess").formatNullable[LibraryAccess] and
    (__ \ "whoCanComment").format[LibraryCommentPermissions]
  )(applyFromDbRow, unlift(unapplyToDbRow))

  def isValidName(name: String): Boolean = {
    name.nonEmpty && name.length <= 200 && !name.contains('"')
  }

  def toLibraryView(lib: Library): LibraryView = LibraryView(id = lib.id, ownerId = lib.ownerId, state = lib.state, seq = lib.seq, kind = lib.kind)

  def toDetailedLibraryView(lib: Library): DetailedLibraryView = DetailedLibraryView(id = lib.id, ownerId = lib.ownerId, state = lib.state,
    seq = lib.seq, kind = lib.kind, memberCount = lib.memberCount, keepCount = lib.keepCount, lastKept = lib.lastKept, lastFollowed = None, visibility = lib.visibility,
    updatedAt = lib.updatedAt, name = lib.name, description = lib.description, color = lib.color, slug = lib.slug, orgId = lib.organizationId)
}

sealed abstract class SortDirection(val value: String)
object SortDirection extends Enumerator[SortDirection] {
  case object ASCENDING extends SortDirection("asc")
  case object DESCENDING extends SortDirection("desc")
  val all = _all.toSet
  def fromStr(str: String): Option[SortDirection] = all.find(_.value == str)
  def apply(str: String) = fromStr(str).getOrElse(throw new Exception(s"could not extract sort direction from $str"))

  implicit val format: Format[SortDirection] = EnumFormat.format(fromStr, _.value)

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[SortDirection] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, SortDirection]] = {
      stringBinder.bind(key, params) map {
        case Right(str) =>
          Right(SortDirection(str))
        case _ => Left("Unable to bind a SortDirection")
      }
    }

    override def unbind(key: String, ordering: SortDirection): String = {
      stringBinder.unbind(key, ordering.value)
    }
  }
}

abstract class LibraryFilter(val value: String)
object LibraryFilter {
  case object OWN extends LibraryFilter("own")
  case object FOLLOWING extends LibraryFilter("following")
  case object INVITED extends LibraryFilter("invited")
  case object ALL extends LibraryFilter("all")
  case object INVALID_FILTER extends LibraryFilter("")

  def apply(value: String) = {
    value match {
      case OWN.value => OWN
      case FOLLOWING.value => FOLLOWING
      case INVITED.value => INVITED
      case ALL.value => ALL
      case _ => INVALID_FILTER
    }
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[LibraryFilter] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, LibraryFilter]] = {
      stringBinder.bind(key, params) map {
        case Right(str) =>
          Right(LibraryFilter(str))
        case _ => Left("Unable to bind a LibraryFilter")
      }
    }

    override def unbind(key: String, ordering: LibraryFilter): String = {
      stringBinder.unbind(key, ordering.value)
    }
  }
}

object LibraryPathHelper {

  def formatLibraryPath(owner: Username, orgHandleOpt: Option[OrganizationHandle], slug: LibrarySlug): String = {
    val handle = orgHandleOpt.map(Handle.fromOrganizationHandle).getOrElse(Handle.fromUsername(owner))
    s"/${handle.value}/${slug.value}"
  }

  def formatLibraryPathUrlEncoded(owner: Username, orgHandleOpt: Option[OrganizationHandle], slug: LibrarySlug): String = {
    val handle = orgHandleOpt.map(Handle.fromOrganizationHandle).getOrElse(Handle.fromUsername(owner))
    s"/${handle.urlEncoded}/${slug.urlEncoded}"
  }

  def formatLibraryPathForOrganization(orgHandle: OrganizationHandle, slug: LibrarySlug): String = {
    s"/${orgHandle.value}/${slug.value}"
  }
}

case class LibraryIdKey(id: Id[Library]) extends Key[Library] {
  override val version = 12
  val namespace = "library_by_id"
  def toKey(): String = id.id.toString
}

class LibraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryIdKey, Library](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryStates extends States[Library]

case class LibrarySlug(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}
object LibrarySlug {
  implicit def format: Format[LibrarySlug] =
    Format(
      __.read[String].map(LibrarySlug(_)),
      Writes { o => JsString(o.value) }
    )

  private val MaxLength = 50
  private val ReservedSlugs = Set("libraries", "connections", "followers", "keeps", "tags", "members")
  private val BeforeTruncate = Seq("[^0-9a-z\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF\\s-]|_" -> "", "\\s+" -> "-", "--+" -> "-", "^-" -> "") map compile
  private val AfterTruncate = Seq("-$" -> "") map compile

  def isValidSlug(slug: String): Boolean = {
    slug.nonEmpty && !slug.contains(' ') && !slug.contains('/') && slug.length <= MaxLength
  }

  def isReservedSlug(slug: String): Boolean = {
    ReservedSlugs.contains(slug)
  }

  def generateFromName(name: String): String = {
    // taken from generateSlug() in angular/src/common/util.js
    val s1 = BeforeTruncate.foldLeft(name.toLowerCase)(replaceAll)
    val s2 = AfterTruncate.foldLeft(s1.take(MaxLength))(replaceAll)
    if (isReservedSlug(s2)) s2 + '-' else s2
  }

  private def compile(pair: (String, String)): (Pattern, String) = Pattern.compile(pair._1) -> pair._2
  private def replaceAll(s: String, op: (Pattern, String)): String = op._1.matcher(s).replaceAll(op._2)

  implicit def pathBinder = new PathBindable[LibrarySlug] {
    override def bind(key: String, value: String): Either[String, LibrarySlug] = Right(LibrarySlug(value))
    override def unbind(key: String, slug: LibrarySlug): String = slug.value
  }
}

sealed abstract class LibraryVisibility(val value: String, val amountOfVisibility: Int) extends Ordered[LibraryVisibility] {
  def compare(that: LibraryVisibility) = this.amountOfVisibility compare that.amountOfVisibility
}

object LibraryVisibility extends Enumerator[LibraryVisibility] {
  case object PUBLISHED extends LibraryVisibility("published", 10) // published library, is discoverable
  case object ORGANIZATION extends LibraryVisibility("organization", 9) // private to everyone but members of organization
  case object DISCOVERABLE extends LibraryVisibility("discoverable", 8) // "help my friends", is discoverable
  case object SECRET extends LibraryVisibility("secret", 1) // secret, not discoverable

  def all = _all.toSet
  def get(str: String) = all.find(_.value == str)
  def apply(str: String) = get(str).getOrElse(throw new Exception(s"unknown library visibility: $str"))

  implicit val format: Format[LibraryVisibility] = Format(
    EnumFormat.reads(get, all.map(_.value)),
    Writes { o => JsString(o.value) }
  )
}

sealed abstract class LibraryKind(val value: String, val priority: Int) {
  def compare(other: LibraryKind): Int = this.priority compare other.priority
}

object LibraryKind extends Enumerator[LibraryKind] {
  case object SYSTEM_MAIN extends LibraryKind("system_main", 0)
  case object SYSTEM_SECRET extends LibraryKind("system_secret", 1)
  case object SYSTEM_ORG_GENERAL extends LibraryKind("system_org_general", 2)
  case object USER_CREATED extends LibraryKind("user_created", 3)
  case object SLACK_CHANNEL extends LibraryKind("slack_channel", 4)

  def all = _all.toSet
  val deprecated: Map[String, LibraryKind] = Map(
    "system_guide" -> USER_CREATED,
    "system_read_it_later" -> USER_CREATED,
    "system_persona" -> USER_CREATED
  )
  def get(str: String): Option[LibraryKind] = all.find(_.value == str) orElse deprecated.get(str)
  def apply(str: String) = get(str).getOrElse(throw new Exception(s"unknown library kind: $str"))

  implicit val format: Format[LibraryKind] = Format(
    EnumFormat.reads(get, all.map(_.value)),
    Writes { o => JsString(o.value) }
  )
}

case class LibraryView(id: Option[Id[Library]], ownerId: Id[User], state: State[Library], seq: SequenceNumber[Library], kind: LibraryKind)

object LibraryView {
  implicit val format = Json.format[LibraryView]
}

case class DetailedLibraryView(id: Option[Id[Library]], ownerId: Id[User], state: State[Library], seq: SequenceNumber[Library],
  kind: LibraryKind, memberCount: Int, keepCount: Int, lastKept: Option[DateTime] = None, lastFollowed: Option[DateTime] = None,
  visibility: LibraryVisibility, updatedAt: DateTime, name: String, description: Option[String], color: Option[LibraryColor], slug: LibrarySlug,
  orgId: Option[Id[Organization]])

object DetailedLibraryView {
  implicit val format = Json.format[DetailedLibraryView]
}

case class BasicLibrary(id: PublicId[Library], name: String, path: String, visibility: LibraryVisibility, color: Option[LibraryColor], slack: Option[LiteLibrarySlackInfo]) {
  def isSecret = visibility == LibraryVisibility.SECRET
  def withPath(path: String): BasicLibrary = this.copy(path = path)
}

object BasicLibrary {
  val fake = BasicLibrary(PublicId("l42424242"), "a deleted library", "/", LibraryVisibility.SECRET, None, None)
  def apply(library: Library, owner: Username, orgHandle: Option[OrganizationHandle], slack: Option[LiteLibrarySlackInfo])(implicit publicIdConfig: PublicIdConfiguration): BasicLibrary = {
    val path = LibraryPathHelper.formatLibraryPath(owner, orgHandle, library.slug)
    BasicLibrary(Library.publicId(library.id.get), library.name, path, library.visibility, library.color, slack)
  }
  implicit val format: Format[BasicLibrary] = Format(
    Reads { js =>
      for {
        id <- (js \ "id").validate[PublicId[Library]]
        name <- (js \ "name").validate[String]
        path <- (js \ "path").validate[String]
        visibility <- (js \ "visibility").validate[LibraryVisibility]
        color = (js \ "color").asOpt[LibraryColor]
        slack = (js \ "slack").asOpt[LiteLibrarySlackInfo]
      } yield BasicLibrary.apply(id, name, path, visibility, color, slack)
    },
    Writes { library => Json.obj("id" -> library.id, "name" -> library.name, "path" -> library.path, "visibility" -> library.visibility, "color" -> library.color, "secret" -> library.isSecret, "slack" -> library.slack) } //todo(Léo): remove secret field }
  )
}

case class BasicLibraryWithUrlIntersection(library: BasicLibrary, urlHash: UrlHash)
object BasicLibraryWithUrlIntersection {
  implicit val writes: Writes[BasicLibraryWithUrlIntersection] = Writes {
    case BasicLibraryWithUrlIntersection(lib, urlHash) => Json.toJson(lib).as[JsObject] ++ Json.obj("intersection" -> s"/int?url=${urlHash.hash}&library=${lib.id.id}")
  }
}

// Replaced by BasicLibraryDetails, please remove dependencies on this
case class BasicLibraryStatistics(memberCount: Int, keepCount: Int)
object BasicLibraryStatistics {
  implicit val format = Json.format[BasicLibraryStatistics]
}

// For service-to-Shoebox calls needing library metadata. Specialized for search's needs, ask search before changing.
final case class BasicLibraryDetails(
    name: String,
    slug: LibrarySlug,
    color: Option[LibraryColor],
    imageUrl: Option[String],
    description: Option[String],
    numFollowers: Int,
    numCollaborators: Int,
    keepCount: Int,
    membership: Option[LibraryMembershipInfo], // viewer
    ownerId: Id[User],
    organizationId: Option[Id[Organization]],
    url: Path,
    permissions: Set[LibraryPermission]) {
  def space: LibrarySpace = LibrarySpace(ownerId, organizationId)
}

object BasicLibraryDetails {
  // TODO(léo): uncomment this line and destroy my travesty
  // implicit val format: Format[BasicLibraryDetails] = Json.format[BasicLibraryDetails]
  implicit val format: Format[BasicLibraryDetails] = (
    (__ \ 'name).format[String] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'color).formatNullable[LibraryColor] and
    (__ \ 'imageUrl).formatNullable[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'numFollowers).format[Int] and
    (__ \ 'numCollaborators).format[Int] and
    (__ \ 'keepCount).format[Int] and
    (__ \ 'membership).formatNullable[LibraryMembershipInfo] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'organizationId).formatNullable[Id[Organization]] and
    (__ \ 'url).format[String].inmap[Path](Path(_), _.absolute) and
    (__ \ 'permissions).format[Set[LibraryPermission]]
  )(BasicLibraryDetails.apply _, unlift(BasicLibraryDetails.unapply))
}

sealed abstract class LibraryColor(val value: String, val hex: String)
object LibraryColor extends Enumerator[LibraryColor] {
  case object BLUE extends LibraryColor("blue", "#447ab7")
  case object SKY_BLUE extends LibraryColor("sky_blue", "#5ab7e7")
  case object GREEN extends LibraryColor("green", "#4fc49e")
  case object ORANGE extends LibraryColor("orange", "#f99457")
  case object RED extends LibraryColor("red", "#dd5c60")
  case object MAGENTA extends LibraryColor("magenta", "#c16c9e")
  case object PURPLE extends LibraryColor("purple", "#9166ac")

  def all = _all.toSet
  def get(str: String) = all.find(c => c.value == str || c.hex == str)
  def apply(str: String): LibraryColor = get(str).getOrElse(throw new Exception(s"Unknown library color $str"))

  implicit val format: Format[LibraryColor] = Format(
    EnumFormat.reads(get, all.map(_.value) ++ all.map(_.hex)),
    Writes { o => JsString(o.hex) }
  )

  val AllColors: IndexedSeq[LibraryColor] = all.toIndexedSeq.sortBy(_.value)
  private lazy val prng = new MersenneTwister(System.currentTimeMillis())

  def byHash(seed: Seq[String]): LibraryColor = {
    val h = MurmurHash3.seqHash(seed).abs
    AllColors(h % AllColors.length)
  }

  def pickRandomLibraryColor(): LibraryColor = {
    AllColors(prng.nextInt(AllColors.length))
  }

}
