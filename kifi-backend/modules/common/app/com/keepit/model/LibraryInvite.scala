package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ CryptoSupport, PublicIdGenerator, ModelWithPublicId }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.social.BasicUser
import com.kifi.macros.json
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import scala.concurrent.duration.Duration
import scala.util.Random

case class LibraryInvite(
    id: Option[Id[LibraryInvite]] = None,
    libraryId: Id[Library],
    inviterId: Id[User],
    userId: Option[Id[User]] = None,
    emailAddress: Option[EmailAddress] = None,
    access: LibraryAccess,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[LibraryInvite] = LibraryInviteStates.ACTIVE,
    authToken: String = RandomStringUtils.randomAlphanumeric(16),
    message: Option[String] = None,
    remindersSent: Int = 0,
    lastReminderSentAt: Option[DateTime] = None) extends ModelWithPublicId[LibraryInvite] with ModelWithState[LibraryInvite] {

  def withId(id: Id[LibraryInvite]): LibraryInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryInvite = this.copy(updatedAt = now)
  def withState(newState: State[LibraryInvite]): LibraryInvite = this.copy(state = newState)

  def isCollaborator = access == LibraryAccess.READ_WRITE
  def isFollower = access == LibraryAccess.READ_ONLY

  override def toString: String = s"LibraryInvite[id=$id,libraryId=$libraryId,ownerId=$inviterId,userId=$userId,email=$emailAddress,access=$access,state=$state,remindersSent=$remindersSent,lastReminderSentAt=$lastReminderSentAt]"
}

object LibraryInvite extends PublicIdGenerator[LibraryInvite] {

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-20, -76, -59, 85, 85, -2, 72, 61, 58, 38, 60, -2, -128, 79, 9, -87))

  def applyFromDbRow(
    id: Option[Id[LibraryInvite]],
    libraryId: Id[Library],
    inviterId: Id[User],
    userId: Option[Id[User]],
    emailAddress: Option[EmailAddress],
    access: LibraryAccess,
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[LibraryInvite],
    authToken: String,
    message: Option[String],
    remindersSent: Int,
    lastReminderSentAt: Option[DateTime]) = {
    LibraryInvite(id, libraryId, inviterId, userId, emailAddress, access, createdAt, updatedAt, state, authToken,
      message, remindersSent, lastReminderSentAt)
  }

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[LibraryInvite]) and
    (__ \ 'libraryId).format[Id[Library]] and
    (__ \ 'inviterId).format[Id[User]] and
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'emailAddress).format[Option[EmailAddress]] and
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[LibraryInvite]) and
    (__ \ 'authToken).format[String] and
    (__ \ 'message).format[Option[String]] and
    (__ \ 'remindersSent).format[Int] and
    (__ \ 'lastReminderSentAt).formatNullable(DateTimeJsonFormat)
  )(LibraryInvite.apply, unlift(LibraryInvite.unapply))
}

case class LibraryInviteInfo(
  access: LibraryAccess,
  lastInvitedAt: DateTime,
  inviter: BasicUser)

object LibraryInviteInfo {
  implicit val internalFormat: Format[LibraryInviteInfo] = (
    (__ \ 'access).format[LibraryAccess] and
    (__ \ 'lastInvitedAt).format[DateTime] and
    (__ \ 'inviter).format[BasicUser]
  )(LibraryInviteInfo.apply, unlift(LibraryInviteInfo.unapply))
}

sealed abstract class LibraryInvitePermissions(val value: String)

object LibraryInvitePermissions {
  case object COLLABORATOR extends LibraryInvitePermissions("collaborator")
  case object OWNER extends LibraryInvitePermissions("owner")

  implicit val format: Format[LibraryInvitePermissions] = Format(
    Reads { j => j.validate[String].map(LibraryInvitePermissions(_)) },
    Writes { o => JsString(o.value) }
  )

  def apply(str: String): LibraryInvitePermissions = {
    str match {
      case COLLABORATOR.value | "collaborators_allow" | "read_write" => COLLABORATOR
      case OWNER.value | "owner_only" => OWNER
    }
  }
}

// Not sure we need this cache?
case class LibraryInviteIdKey(id: Id[LibraryInvite]) extends Key[LibraryInvite] {
  val namespace = "library_invite_by_id"
  override val version: Int = 2
  def toKey(): String = id.id.toString
}
class LibraryInviteIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[LibraryInviteIdKey, LibraryInvite](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object LibraryInviteStates extends States[LibraryInvite] {
  val ACCEPTED = State[LibraryInvite]("accepted")
  val DECLINED = State[LibraryInvite]("declined")

  val notActive = Set(ACCEPTED, DECLINED, INACTIVE)
}
