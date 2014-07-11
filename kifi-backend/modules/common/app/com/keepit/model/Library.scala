package com.keepit.model

import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class Library (
                 id: Option[Id[Library]] = None,
                 createdAt: DateTime = currentDateTime,
                 updatedAt: DateTime = currentDateTime,
                 externalId: ExternalId[Library] = ExternalId(),
                 name: String,
                 ownerId: Id[User],
                 privacy: LibraryPrivacy = LibraryPrivacy.PUBLIC,
                 description: Option[String] = None,
                 // Token name code: Set[String]
                 state: State[Library] = LibraryStates.ACTIVE,
                 seq: SequenceNumber[Library] = SequenceNumber.ZERO
                 ) extends ModelWithExternalId[Library] with ModelWithState[Library] with ModelWithSeqNumber[Library] {

  def withId(id: Id[Library]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withName(myName: String) = this.copy(name = myName)
  def withOwner(id: Id[User]) = this.copy(ownerId = id)
  def withExternalId(id: ExternalId[Library]) = copy(externalId = id)
  def withPrivacy(level: String) = this.copy(privacy = level match {
    case "private" => LibraryPrivacy.PRIVATE
    case "shared" => LibraryPrivacy.SHARED
    case "public" => LibraryPrivacy.PUBLIC
  })
  def withState(myState: State[Library]) = this.copy(state = myState)
  def withDescription(descript: String) = this.copy(description = Some(descript))

  override def toString(): String = s"Library[id=$id,externalId=$externalId,name=$name,privacy=$privacy]"

}

object Library {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[Library]) and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'externalId).format(ExternalId.format[Library]) and
      (__ \ 'name).format[String] and
      (__ \ 'ownerId).format[Id[User]] and
      (__ \ 'privacy).format[LibraryPrivacy] and
      (__ \ 'description).format[Option[String]] and
      (__ \ 'state).format(State.format[Library]) and
      (__ \ 'seq).format(SequenceNumber.format[Library])
    )(Library.apply, unlift(Library.unapply))
}




case class LibraryIdKey(id: Id[Library]) extends Key[Library] {
  override val version = 0
  val namespace = "library_by_id"
  def toKey(): String = id.id.toString
}
class LibraryIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryIdKey, Library](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)




case class LibraryExternalIdKey(externalId: ExternalId[Library]) extends Key[Library] {
  override val version = 0
  val namespace = "library_by_external_id"
  def toKey(): String = externalId.id
}
class LibraryExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryExternalIdKey, Library](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)


object LibraryStates extends States[Library] {
  val PENDING = State[Library]("pending")
  val BLOCKED = State[Library]("blocked")
  val INCOMPLETE_SIGNUP = State[Library]("incomplete_signup")
}

case class LibraryPrivacy(value: String)
object LibraryPrivacy {
  implicit def format[T]: Format[LibraryPrivacy] =
    Format(__.read[String].map(LibraryPrivacy(_)), new Writes[LibraryPrivacy]{ def writes(o: LibraryPrivacy) = JsString(o.value) })
  val PUBLIC = LibraryPrivacy("public")
  val PRIVATE = LibraryPrivacy("private")
  val SHARED = LibraryPrivacy("shared")
}