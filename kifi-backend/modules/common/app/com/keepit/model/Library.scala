package com.keepit.model

import javax.crypto.spec.IvParameterSpec
import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ ModelWithPublicIdCompanion, ModelWithPublicId }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.social.BasicUser
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

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
    memberCount: Int) extends ModelWithPublicId[Library] with ModelWithState[Library] with ModelWithSeqNumber[Library] {

  def withId(id: Id[Library]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(myState: State[Library]) = this.copy(state = myState)
}

object Library extends ModelWithPublicIdCompanion[Library] {

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
    (__ \ 'memberCount).format[Int]
  )(Library.apply, unlift(Library.unapply))

  val maxNameLength = 50
  def isValidName(name: String): Boolean = {
    (name != "") && !(name.length > maxNameLength) && !(name.contains("\"")) && !(name.contains("/"))
  }

  def formatLibraryPath(ownerUsername: Option[Username], ownerExternalId: ExternalId[User], slug: LibrarySlug): String = {
    val usernameString = if (ownerUsername.isEmpty) ownerExternalId.id else ownerUsername.get.value
    s"/$usernameString/${slug.value}"
  }

  def toLibraryView(lib: Library): LibraryView = LibraryView(id = lib.id, ownerId = lib.ownerId, state = lib.state, seq = lib.seq, kind = lib.kind)
}

case class LibraryIdKey(id: Id[Library]) extends Key[Library] {
  override val version = 2
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
  trait SystemGenerated extends LibraryKind

  case object SYSTEM_MAIN extends LibraryKind("system_main") with SystemGenerated
  case object SYSTEM_SECRET extends LibraryKind("system_secret") with SystemGenerated
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

case class LibraryAndMemberships(library: Library, memberships: Seq[LibraryMembership])

object LibraryAndMemberships {
  implicit val format = Json.format[LibraryAndMemberships]
}

case class LibraryView(id: Option[Id[Library]], ownerId: Id[User], state: State[Library], seq: SequenceNumber[Library], kind: LibraryKind)

object LibraryView {
  implicit val format = Json.format[LibraryView]
}
