package com.keepit.model

import java.net.URLEncoder
import java.util.regex.Pattern
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration, ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.strings.UTF8
import com.keepit.common.time._
import com.keepit.model.view.LibraryMembershipView
import com.keepit.social.BasicUser
import com.kifi.macros.json

import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.concurrent.duration.Duration

import scala.util.Random

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
    organizationId: Option[Id[Organization]] = None) extends ModelWithPublicId[Library] with ModelWithState[Library] with ModelWithSeqNumber[Library] {

  def sanitizeForDelete: Library = this.copy(
    name = RandomStringUtils.randomAlphanumeric(20),
    description = None,
    state = LibraryStates.INACTIVE,
    slug = LibrarySlug(RandomStringUtils.randomAlphanumeric(20)))

  def withId(id: Id[Library]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(myState: State[Library]) = this.copy(state = myState)
  val isPublished: Boolean = visibility == LibraryVisibility.PUBLISHED
  val isSecret: Boolean = visibility == LibraryVisibility.SECRET

  def space: LibrarySpace = LibrarySpace(ownerId, organizationId)
}

object Library extends ModelWithPublicIdCompanion[Library] {

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
    organizationId: Option[Id[Organization]] = None) = {
    Library(id, createdAt, updatedAt, getDisplayName(name, kind), ownerId, visibility, description, slug, color, state, seq, kind, universalLink, memberCount, lastKept, keepCount, whoCanInvite, organizationId)
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
      lib.organizationId)
  }

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-72, -49, 51, -61, 42, 43, 123, -61, 64, 122, -121, -55, 117, -51, 12, 21))

  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Library]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'name).format[String] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'description).format[Option[String]] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'color).formatNullable[LibraryColor] and
    (__ \ 'state).format(State.format[Library]) and
    (__ \ 'seq).format(SequenceNumber.format[Library]) and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'universalLink).format[String] and
    (__ \ 'memberCount).format[Int] and
    (__ \ 'lastKept).formatNullable[DateTime] and
    (__ \ 'keepCount).format[Int] and
    (__ \ 'whoCanInvite).formatNullable[LibraryInvitePermissions] and
    (__ \ "orgId").formatNullable[Id[Organization]]
  )(Library.apply, unlift(Library.unapply))

  def isValidName(name: String): Boolean = {
    name.nonEmpty && name.length <= 200 && !name.contains('"') && !name.contains('/')
  }

  def toLibraryView(lib: Library): LibraryView = LibraryView(id = lib.id, ownerId = lib.ownerId, state = lib.state, seq = lib.seq, kind = lib.kind)

  def toDetailedLibraryView(lib: Library): DetailedLibraryView = DetailedLibraryView(id = lib.id, ownerId = lib.ownerId, state = lib.state,
    seq = lib.seq, kind = lib.kind, memberCount = lib.memberCount, keepCount = lib.keepCount, lastKept = lib.lastKept, lastFollowed = None, visibility = lib.visibility,
    updatedAt = lib.updatedAt, name = lib.name, description = lib.description, color = lib.color, slug = lib.slug, orgId = lib.organizationId)
}

sealed abstract class SortDirection(val value: String)
object SortDirection {
  case object ASCENDING extends SortDirection("asc")
  case object DESCENDING extends SortDirection("desc")
  def apply(value: String) = value match {
    case ASCENDING.value => ASCENDING
    case DESCENDING.value => DESCENDING
  }

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

  private def getHandle(owner: BasicUser, orgHandleOpt: Option[OrganizationHandle]): Handle = {
    orgHandleOpt match {
      case Some(orgHandle) => orgHandle
      case None => owner.username
    }
  }

  def formatLibraryPath(owner: BasicUser, orgHandleOpt: Option[OrganizationHandle], slug: LibrarySlug): String = {
    val handle = getHandle(owner, orgHandleOpt)
    s"/${handle.value}/${slug.value}"
  }

  def formatLibraryPathUrlEncoded(owner: BasicUser, orgHandleOpt: Option[OrganizationHandle], slug: LibrarySlug): String = {
    val handle = getHandle(owner, orgHandleOpt)
    s"/${handle.urlEncoded}/${slug.urlEncoded}"
  }
}

case class LibraryIdKey(id: Id[Library]) extends Key[Library] {
  override val version = 7
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
    Format(__.read[String].map(LibrarySlug(_)), new Writes[LibrarySlug] { def writes(o: LibrarySlug) = JsString(o.value) })

  private val MaxLength = 50
  private val ReservedSlugs = Set("libraries", "connections", "followers", "keeps", "tags")
  private val BeforeTruncate = Seq("[^\\w\\s-]|_" -> "", "(\\s|--)+" -> "-", "^-" -> "") map compile
  private val AfterTruncate = Seq("-$" -> "") map compile

  def isValidSlug(slug: String): Boolean = {
    slug.nonEmpty && !slug.contains(' ') && slug.length <= MaxLength
  }

  def isReservedSlug(slug: String): Boolean = {
    ReservedSlugs.contains(slug)
  }

  def generateFromName(name: String): String = {
    // taken from generateSlug() in angular/src/common/util.js
    val s1 = BeforeTruncate.foldLeft(name.toLowerCase())(replaceAll)
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

sealed abstract class LibraryVisibility(val value: String)

object LibraryVisibility {
  case object PUBLISHED extends LibraryVisibility("published") // published library, is discoverable
  case object DISCOVERABLE extends LibraryVisibility("discoverable") // "help my friends", is discoverable
  case object ORGANIZATION extends LibraryVisibility("organization") // private to everyone but members of organization
  case object SECRET extends LibraryVisibility("secret") // secret, not discoverable

  implicit def format[T]: Format[LibraryVisibility] =
    Format(__.read[String].map(LibraryVisibility(_)), new Writes[LibraryVisibility] { def writes(o: LibraryVisibility) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case PUBLISHED.value => PUBLISHED
      case DISCOVERABLE.value => DISCOVERABLE
      case ORGANIZATION.value => ORGANIZATION
      case SECRET.value => SECRET
    }
  }
}

sealed abstract class LibraryKind(val value: String, val priority: Int) {
  def compare(other: LibraryKind): Int = this.priority compare other.priority
}

object LibraryKind {
  case object SYSTEM_MAIN extends LibraryKind("system_main", 0)
  case object SYSTEM_SECRET extends LibraryKind("system_secret", 1)
  case object SYSTEM_PERSONA extends LibraryKind("system_persona", 2)
  case object SYSTEM_READ_IT_LATER extends LibraryKind("system_read_it_later", 2)
  case object SYSTEM_GUIDE extends LibraryKind("system_guide", 3)
  case object USER_CREATED extends LibraryKind("user_created", 2)

  implicit def format[T]: Format[LibraryKind] =
    Format(__.read[String].map(LibraryKind(_)), new Writes[LibraryKind] { def writes(o: LibraryKind) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case SYSTEM_MAIN.value => SYSTEM_MAIN
      case SYSTEM_SECRET.value => SYSTEM_SECRET
      case SYSTEM_PERSONA.value => SYSTEM_PERSONA
      case SYSTEM_READ_IT_LATER.value => SYSTEM_READ_IT_LATER
      case "system_read_id_later" => SYSTEM_READ_IT_LATER //for backward compatibility. I'll update the db and clear the cache. can remove by Aug 6, 2015
      case SYSTEM_GUIDE.value => SYSTEM_GUIDE
      case USER_CREATED.value => USER_CREATED
      case _ => throw new Exception(s"unknown library kind: $str")
    }
  }
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

case class BasicLibrary(id: PublicId[Library], name: String, path: String, visibility: LibraryVisibility, color: Option[LibraryColor]) {
  def isSecret = visibility == LibraryVisibility.SECRET
}

object BasicLibrary {
  def apply(library: Library, owner: BasicUser, org: Option[Organization])(implicit publicIdConfig: PublicIdConfiguration): BasicLibrary = {
    val path = LibraryPathHelper.formatLibraryPath(owner, org.map(_.handle), library.slug)
    BasicLibrary(Library.publicId(library.id.get), library.name, path, library.visibility, library.color)
  }
}

// Replaced by BasicLibraryDetails, please remove dependencies on this
case class BasicLibraryStatistics(memberCount: Int, keepCount: Int)
object BasicLibraryStatistics {
  implicit val format = Json.format[BasicLibraryStatistics]
}

// For service-to-Shoebox calls needing library metadata. Specialized for search's needs, ask search before changing.
@json
case class BasicLibraryDetails(
  name: String,
  slug: LibrarySlug,
  color: Option[LibraryColor],
  imageUrl: Option[String],
  description: Option[String],
  numFollowers: Int,
  numCollaborators: Int,
  keepCount: Int,
  membership: Option[LibraryMembership] // viewer
  )

sealed abstract class LibraryColor(val hex: String)
object LibraryColor {

  implicit def format[T]: Format[LibraryColor] =
    Format(__.read[String].map(LibraryColor(_)), new Writes[LibraryColor] { def writes(o: LibraryColor) = JsString(o.hex) })

  case object BLUE extends LibraryColor("#447ab7")
  case object SKY_BLUE extends LibraryColor("#5ab7e7")
  case object GREEN extends LibraryColor("#4fc49e")
  case object ORANGE extends LibraryColor("#f99457")
  case object RED extends LibraryColor("#dd5c60")
  case object MAGENTA extends LibraryColor("#c16c9e")
  case object PURPLE extends LibraryColor("#9166ac")

  def apply(str: String): LibraryColor = {
    str match {
      case "blue" | BLUE.hex => BLUE
      case "sky_blue" | SKY_BLUE.hex => SKY_BLUE
      case "green" | GREEN.hex => GREEN
      case "orange" | ORANGE.hex => ORANGE
      case "red" | RED.hex => RED
      case "magenta" | MAGENTA.hex => MAGENTA
      case "purple" | PURPLE.hex => PURPLE
    }
  }

  val AllColors: Seq[LibraryColor] = Seq(BLUE, SKY_BLUE, GREEN, ORANGE, RED, MAGENTA, PURPLE)

  private lazy val rnd = new Random

  def pickRandomLibraryColor(): LibraryColor = {
    AllColors(rnd.nextInt(AllColors.size))
  }

}
