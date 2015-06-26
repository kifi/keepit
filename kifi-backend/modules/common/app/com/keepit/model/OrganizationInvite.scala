package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationInvite(
    id: Option[Id[OrganizationInvite]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE,
    decision: InvitationDecision = InvitationDecision.PENDING,
    organizationId: Id[Organization],
    inviterId: Id[User],
    userId: Option[Id[User]] = None,
    emailAddress: Option[EmailAddress] = None,
    role: OrganizationRole,
    message: Option[String] = None,
    authToken: String = RandomStringUtils.randomAlphanumeric(9)) extends ModelWithPublicId[OrganizationInvite] with ModelWithState[OrganizationInvite] {

  def withId(id: Id[OrganizationInvite]): OrganizationInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationInvite = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationInvite]): OrganizationInvite = this.copy(state = newState)

  override def toString: String = s"OrganizationInvite[id=$id,organizationId=$organizationId,ownerId=$inviterId,userId=$userId,email=$emailAddress,role=$role,state=$state]"

}

// doesn't need to be specific to just OrganizationInvite, could be re-used later.
abstract class InvitationDecision(val value: String)
object InvitationDecision {
  case object ACCEPTED extends InvitationDecision("accepted")
  case object DECLINED extends InvitationDecision("declined")
  case object PENDING extends InvitationDecision("pending")

  def apply(value: String) = value match {
    case ACCEPTED.value => ACCEPTED
    case DECLINED.value => DECLINED
    case PENDING.value => PENDING
  }
  implicit def format[T]: Format[InvitationDecision] =
    Format(__.read[String].map(InvitationDecision(_)), new Writes[InvitationDecision] { def writes(o: InvitationDecision) = JsString(o.value) })
}

object OrganizationInvite extends ModelWithPublicIdCompanion[OrganizationInvite] {

  protected[this] val publicIdPrefix = "o"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-66, -82, -35, -48, -88, 55, -82, 53, -38, 123, 92, 62, -14, -35, 95, -93))

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationInvite]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[OrganizationInvite]) and
    (__ \ "decision").format[InvitationDecision] and
    (__ \ 'organizationId).format[Id[Organization]] and
    (__ \ 'inviterId).format[Id[User]] and
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'emailAddress).format[Option[EmailAddress]] and
    (__ \ 'role).format[OrganizationRole] and
    (__ \ 'message).format[Option[String]] and
    (__ \ 'authToken).format[String]
  )(OrganizationInvite.apply, unlift(OrganizationInvite.unapply))

  implicit def ord: Ordering[OrganizationInvite] = new Ordering[OrganizationInvite] {
    def compare(x: OrganizationInvite, y: OrganizationInvite): Int = x.role.priority compare y.role.priority
  }
}

object OrganizationInviteStates extends States[OrganizationInvite]
