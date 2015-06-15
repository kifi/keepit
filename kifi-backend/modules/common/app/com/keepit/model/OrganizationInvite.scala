package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationInvite(
    id: Option[Id[OrganizationInvite]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE,
    organizationId: Id[Organization],
    inviterId: Id[User],
    userId: Option[Id[User]] = None,
    emailAddress: Option[EmailAddress] = None,
    role: OrganizationRole,
    message: Option[String] = None) extends ModelWithPublicId[OrganizationInvite] with ModelWithState[OrganizationInvite] {

  def withId(id: Id[OrganizationInvite]): OrganizationInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationInvite = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationInvite]): OrganizationInvite = this.copy(state = newState)

  override def toString: String = s"OrganizationInvite[id=$id,organizationId=$organizationId,ownerId=$inviterId,userId=$userId,email=$emailAddress,role=$role,state=$state]"

}

object OrganizationInvite extends ModelWithPublicIdCompanion[OrganizationInvite] {

  protected[this] val publicIdPrefix = "o"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-66, -82, -35, -48, -88, 55, -82, 53, -38, 123, 92, 62, -14, -35, 95, -93))

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationInvite]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[OrganizationInvite]) and
    (__ \ 'organizationId).format[Id[Organization]] and
    (__ \ 'inviterId).format[Id[User]] and
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'emailAddress).format[Option[EmailAddress]] and
    (__ \ 'role).format[OrganizationRole] and
    (__ \ 'message).format[Option[String]]
  )(OrganizationInvite.apply, unlift(OrganizationInvite.unapply))

  implicit def ord: Ordering[OrganizationInvite] = new Ordering[OrganizationInvite] {
    def compare(x: OrganizationInvite, y: OrganizationInvite): Int = x.role.priority compare y.role.priority
  }
}

object OrganizationInviteStates extends States[OrganizationInvite] {
  val ACCEPTED = State[OrganizationInvite]("accepted")
  val DECLINED = State[OrganizationInvite]("declined")
}
