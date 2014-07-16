package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class Library(
    id: Option[Id[Library]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    externalId: ExternalId[Library] = ExternalId(),
    name: String,
    ownerId: Id[User],
    visibility: LibraryVisibility,
    description: Option[String] = None,
    slug: LibrarySlug,
    state: State[Library] = LibraryStates.ACTIVE,
    seq: SequenceNumber[Library] = SequenceNumber.ZERO) extends ModelWithExternalId[Library] with ModelWithState[Library] with ModelWithSeqNumber[Library] {

  def withId(id: Id[Library]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(myState: State[Library]) = this.copy(state = myState)

  override def toString(): String = s"Library[id=$id,externalId=$externalId,name=$name,privacy=$visibility]"

}

object Library {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Library]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[Library]) and
    (__ \ 'name).format[String] and
    (__ \ 'ownerId).format[Id[User]] and
    (__ \ 'visibility).format[LibraryVisibility] and
    (__ \ 'description).format[Option[String]] and
    (__ \ 'slug).format[LibrarySlug] and
    (__ \ 'state).format(State.format[Library]) and
    (__ \ 'seq).format(SequenceNumber.format[Library])
  )(Library.apply, unlift(Library.unapply))

  val maxNameLength = 50
  def isValidName(name: String): Boolean = {
    !(name.length > maxNameLength) || (name.contains("\""))
  }
}

case class LibraryIdKey(id: Id[Library]) extends Key[Library] {
  override val version = 0
  val namespace = "library_by_id"
  def toKey(): String = id.id.toString
}
class LibraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryIdKey, Library](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class LibraryExternalIdKey(externalId: ExternalId[Library]) extends Key[Library] {
  override val version = 0
  val namespace = "library_by_external_id"
  def toKey(): String = externalId.id
}
class LibraryExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryExternalIdKey, Library](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryStates extends States[Library]

case class LibrarySlug(value: String)
object LibrarySlug {
  implicit def format: Format[LibrarySlug] =
    Format(__.read[String].map(LibrarySlug(_)), new Writes[LibrarySlug] { def writes(o: LibrarySlug) = JsString(o.value) })

  val maxSlugLength = 50
  def isValidSlug(slug: String): Boolean = {
    (!slug.contains(' ') && slug.length < maxSlugLength)
  }
}

sealed abstract class LibraryVisibility(val value: String)

object LibraryVisibility {
  case object ANYONE extends LibraryVisibility("anyone")
  case object LIMITED extends LibraryVisibility("limited")
  case object SECRET extends LibraryVisibility("secret")

  implicit def format[T]: Format[LibraryVisibility] =
    Format(__.read[String].map(LibraryVisibility(_)), new Writes[LibraryVisibility] { def writes(o: LibraryVisibility) = JsString(o.value) })

  def apply(str: String) = {
    str match {
      case ANYONE.value => ANYONE
      case LIMITED.value => LIMITED
      case SECRET.value => SECRET
    }
  }
}
