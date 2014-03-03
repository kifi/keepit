package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Invitation(
  id: Option[Id[Invitation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Invitation] = ExternalId(),
  senderUserId: Option[Id[User]],
  recipientSocialUserId: Option[Id[SocialUserInfo]],
  recipientEContactId: Option[Id[EContact]] = None,
  state: State[Invitation] = InvitationStates.ACTIVE,
  seq: SequenceNumber[Invitation]
) extends ModelWithExternalId[Invitation] with ModelWithState[Invitation] with ModelWithSeqNumber[Invitation] {
  def withId(id: Id[Invitation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[Invitation]) = copy(state = state)
  def withRecipientSocialUserId(recipientSocialUserId: Option[Id[SocialUserInfo]]) = copy(recipientSocialUserId = recipientSocialUserId)
}

object InvitationStates extends States[Invitation] {
  // active means "invitation is active and may be accepted"
  val ACCEPTED = State[Invitation]("accepted") // the invited person has created a kifi account
  val ADMIN_REJECTED = State[Invitation]("admin_rejected")
  val ADMIN_ACCEPTED = State[Invitation]("admin_accepted")
  val JOINED = State[Invitation]("joined") // the invited person, after approval, has come back to kifi and logged in
}

object Invitation {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[Invitation]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'externalId).format(ExternalId.format[Invitation]) and
      (__ \ 'sendUserId).formatNullable(Id.format[User]) and
      (__ \ 'recipientSocialUserId).formatNullable(Id.format[SocialUserInfo]) and
      (__ \ 'recipientEContactId).formatNullable(Id.format[EContact]) and
      (__ \ 'state).format(State.format[Invitation]) and
      (__ \ 'seq).format(SequenceNumber.format[Invitation])
    )(Invitation.apply, unlift(Invitation.unapply))
}
