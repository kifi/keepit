package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration, ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.model.view.LibraryMembershipView
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration
import com.keepit.social.BasicUser
import com.keepit.common.json

case class Library(
    id: Option[Id[Library]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    name: String,
    ownerId: Id[User],
    visibility: LibraryVisibility,
    description: Option[String] = None,
    slug: LibrarySlug,
    state: State[Library] = LibraryStates.ACTIVE,
    seq: SequenceNumber[Library] = SequenceNumber.ZERO,
    kind: LibraryKind = LibraryKind.USER_CREATED,
    universalLink: String = RandomStringUtils.randomAlphanumeric(40),
    memberCount: Int,
    lastKept: Option[DateTime] = None) extends ModelWithPublicId[Library] with ModelWithState[Library] with ModelWithSeqNumber[Library] {

  def sanitizeForDelete(): Library = copy(
    name = RandomStringUtils.randomAlphanumeric(20),
    description = None,
    state = LibraryStates.INACTIVE,
    slug = LibrarySlug(RandomStringUtils.randomAlphanumeric(20)))

  def withId(id: Id[Library]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(myState: State[Library]) = this.copy(state = myState)
  val isDisjoint: Boolean = kind match {
    case (LibraryKind.SYSTEM_MAIN | LibraryKind.SYSTEM_SECRET) => true
    case _ => false
  }
}

object Library extends ModelWithPublicIdCompanion[Library] {

  val SYSTEM_MAIN_DISPLAY_NAME = "My Main Library"
  val SYSTEM_SECRET_DISPLAY_NAME = "My Private Library"

  def getDisplayName(name: String, kind: LibraryKind): String = kind match {
    case LibraryKind.SYSTEM_MAIN => SYSTEM_MAIN_DISPLAY_NAME
    case LibraryKind.SYSTEM_SECRET => SYSTEM_SECRET_DISPLAY_NAME
    case _ => name
  }

  // is_primary: trueOrNull in db
  def applyFromDbRow(id: Option[Id[Library]], createdAt: DateTime, updatedAt: DateTime, name: String, ownerId: Id[User], visibility: LibraryVisibility, description: Option[String], slug: LibrarySlug, state: State[Library], seq: SequenceNumber[Library], kind: LibraryKind, universalLink: String, memberCount: Int, lastKept: Option[DateTime]) = {
    Library(id, createdAt, updatedAt, getDisplayName(name, kind), ownerId, visibility, description, slug, state, seq, kind, universalLink, memberCount, lastKept)
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
    (__ \ 'state).format(State.format[Library]) and
    (__ \ 'seq).format(SequenceNumber.format[Library]) and
    (__ \ 'kind).format[LibraryKind] and
    (__ \ 'universalLink).format[String] and
    (__ \ 'memberCount).format[Int] and
    (__ \ 'lastKept).formatNullable[DateTime]
  )(Library.apply, unlift(Library.unapply))

  val maxNameLength = 200
  def isValidName(name: String): Boolean = {
    (name != "") && !(name.length > maxNameLength) && !(name.contains("\"")) && !(name.contains("/"))
  }

  def formatLibraryPath(ownerUsername: Username, ownerExternalId: ExternalId[User], slug: LibrarySlug): String = {
    val usernameString = ownerUsername.value
    s"/$usernameString/${slug.value}"
  }

  def toLibraryView(lib: Library): LibraryView = LibraryView(id = lib.id, ownerId = lib.ownerId, state = lib.state, seq = lib.seq, kind = lib.kind)
}

case class LibraryMetadataKey(id: Id[Library]) extends Key[String] {
  override val version = 3
  val namespace = "library_metadata_by_id"
  def toKey(): String = id.id.toString
}

class LibraryMetadataCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryMetadataKey, String](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryIdKey(id: Id[Library]) extends Key[Library] {
  override val version = 3
  val namespace = "library_by_id"
  def toKey(): String = id.id.toString
}

class LibraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryIdKey, Library](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryStates extends States[Library]

case class LibrarySlug(value: String)
object LibrarySlug {
  implicit def format: Format[LibrarySlug] =
    Format(__.read[String].map(LibrarySlug(_)), new Writes[LibrarySlug] { def writes(o: LibrarySlug) = JsString(o.value) })

  val maxSlugLength = 50
  def isValidSlug(slug: String): Boolean = {
    slug != "" && !slug.contains(' ') && slug.length < maxSlugLength
  }

  def generateFromName(name: String): String = {
    name.toLowerCase().replaceAll("[^\\w\\s]|_", "").replaceAll("\\s+", "").replaceAll("^-", "") // taken from generateSlug() in  manageLibrary.js
  }
}

sealed abstract class LibraryVisibility(val value: String)

object LibraryVisibility {
  case object PUBLISHED extends LibraryVisibility("published") // published library, is discoverable
  case object DISCOVERABLE extends LibraryVisibility("discoverable") // "help my friends", is discoverable
  case object SECRET extends LibraryVisibility("secret") // secret, not discoverable

  implicit def format[T]: Format[LibraryVisibility] =
    Format(__.read[String].map(LibraryVisibility(_)), new Writes[LibraryVisibility] { def writes(o: LibraryVisibility) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case PUBLISHED.value => PUBLISHED
      case DISCOVERABLE.value => DISCOVERABLE
      case SECRET.value => SECRET
    }
  }
}

sealed abstract class LibraryKind(val value: String)

object LibraryKind {
  case object SYSTEM_MAIN extends LibraryKind("system_main")
  case object SYSTEM_SECRET extends LibraryKind("system_secret")
  case object USER_CREATED extends LibraryKind("user_created")

  implicit def format[T]: Format[LibraryKind] =
    Format(__.read[String].map(LibraryKind(_)), new Writes[LibraryKind] { def writes(o: LibraryKind) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case SYSTEM_MAIN.value => SYSTEM_MAIN
      case SYSTEM_SECRET.value => SYSTEM_SECRET
      case USER_CREATED.value => USER_CREATED
    }
  }
}

case class LibraryAndMemberships(library: Library, memberships: Seq[LibraryMembershipView])

object LibraryAndMemberships {
  implicit val format = Json.format[LibraryAndMemberships]
}

case class LibraryView(id: Option[Id[Library]], ownerId: Id[User], state: State[Library], seq: SequenceNumber[Library], kind: LibraryKind)

object LibraryView {
  implicit val format = Json.format[LibraryView]
}

case class BasicLibrary(id: PublicId[Library], name: String, path: String, secret: Boolean)

object BasicLibrary {
  implicit val writes = Writes[BasicLibrary] { library =>
    Json.obj("id" -> library.id, "name" -> library.name, "path" -> library.path, "secret" -> library.secret)
  }
  def apply(library: Library, owner: BasicUser)(implicit publicIdConfig: PublicIdConfiguration): BasicLibrary = {
    val path = Library.formatLibraryPath(owner.username, owner.externalId, library.slug)
    BasicLibrary(Library.publicId(library.id.get), library.name, path, library.visibility == LibraryVisibility.SECRET)
  }
}
