package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.crypto.{ CryptoSupport, ModelWithPublicIdCompanion, ModelWithPublicId }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.shoebox.Words
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
    authToken: String = RandomStringUtils.randomAlphanumeric(7),
    message: Option[String] = None) extends ModelWithPublicId[LibraryInvite] with ModelWithState[LibraryInvite] {

  def withId(id: Id[LibraryInvite]): LibraryInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): LibraryInvite = this.copy(updatedAt = now)
  def withState(newState: State[LibraryInvite]): LibraryInvite = this.copy(state = newState)

  def isCollaborator = (access == LibraryAccess.READ_WRITE) || (access == LibraryAccess.READ_INSERT)
  def isFollower = (access == LibraryAccess.READ_ONLY)

  override def toString: String = s"LibraryInvite[id=$id,libraryId=$libraryId,ownerId=$inviterId,userId=$userId,email=$emailAddress,access=$access,state=$state]"
}

object LibraryInvite extends ModelWithPublicIdCompanion[LibraryInvite] {

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-20, -76, -59, 85, 85, -2, 72, 61, 58, 38, 60, -2, -128, 79, 9, -87))

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
    (__ \ 'message).format[Option[String]]
  )(LibraryInvite.apply, unlift(LibraryInvite.unapply))

  implicit def ord: Ordering[LibraryInvite] = new Ordering[LibraryInvite] {
    def compare(x: LibraryInvite, y: LibraryInvite): Int = x.access.priority compare y.access.priority
  }
}

@json case class LibraryInviteInfo(inviter: BasicUser, access: LibraryAccess, message: Option[String], lastInvite: Long)
object LibraryInviteInfo {
  def createInfo(invite: LibraryInvite, inviter: BasicUser): LibraryInviteInfo = {
    LibraryInviteInfo(inviter, invite.access, invite.message, invite.createdAt.getMillis)
  }
}

sealed abstract class LibraryInvitePermissions(val value: String)

object LibraryInvitePermissions {
  case object COLLABORATOR extends LibraryInvitePermissions("collaborator")
  case object OWNER extends LibraryInvitePermissions("owner")

  implicit def format[T]: Format[LibraryInvitePermissions] =
    Format(__.read[String].map(LibraryInvitePermissions(_)), new Writes[LibraryInvitePermissions] { def writes(o: LibraryInvitePermissions) = JsString(o.value) })

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
}
