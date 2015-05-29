package com.keepit.model

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.crypto.{ModelWithPublicIdCompanion, ModelWithPublicId}
import com.keepit.common.db.{States, ModelWithState, State, Id}
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationInvite(
                               id: Option[Id[OrganizationInvite]] = None,
                               organizationId: Id[Organization],
                               inviterId: Id[User],
                               userId: Option[Id[User]] = None,
                               emailAddress: Option[EmailAddress] = None,
                               access: OrganizationAccess,
                               createdAt: DateTime = currentDateTime,
                               updatedAt: DateTime = currentDateTime,
                               state: State[OrganizationInvite] = OrganizationInviteStates.ACTIVE,
                               authToken: String = RandomStringUtils.randomAlphanumeric(7),
                               message: Option[String] = None) extends ModelWithPublicId[OrganizationInvite] with ModelWithState[OrganizationInvite] {

  def withId(id: Id[OrganizationInvite]): OrganizationInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationInvite = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationInvite]): OrganizationInvite = this.copy(state = newState)

  def isCollaborator = (access == OrganizationAccess.READ_WRITE) || (access == OrganizationAccess.READ_INSERT)
  def isFollower = (access == OrganizationAccess.READ_ONLY)

  override def toString: String = s"OrganizationInvite[id=$id,organizationId=$organizationId,ownerId=$inviterId,userId=$userId,email=$emailAddress,access=$access,state=$state]"

}

object OrganizationInvite extends ModelWithPublicIdCompanion[OrganizationInvite] {

  protected[this] val publicIdPrefix = "l"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-20, -76, -59, 85, 85, -2, 72, 61, 58, 38, 60, -2, -128, 79, 9, -87))

  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationInvite]) and
      (__ \ 'organizationId).format[Id[Organization]] and
      (__ \ 'inviterId).format[Id[User]] and
      (__ \ 'userId).format[Option[Id[User]]] and
      (__ \ 'emailAddress).format[Option[EmailAddress]] and
      (__ \ 'access).format[OrganizationAccess] and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'state).format(State.format[OrganizationInvite]) and
      (__ \ 'authToken).format[String] and
      (__ \ 'message).format[Option[String]]
    )(OrganizationInvite.apply, unlift(OrganizationInvite.unapply))

  implicit def ord: Ordering[OrganizationInvite] = new Ordering[OrganizationInvite] {
    def compare(x: OrganizationInvite, y: OrganizationInvite): Int = x.access.priority compare y.access.priority
  }
}

object OrganizationInviteStates extends States[OrganizationInvite] {
  val ACCEPTED = State[OrganizationInvite]("accepted")
  val DECLINED = State[OrganizationInvite]("declined")
}