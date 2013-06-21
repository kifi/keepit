package com.keepit.model

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.common.time._

case class Invitation(
  id: Option[Id[Invitation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Invitation] = ExternalId(),
  senderUserId: Option[Id[User]],
  recipientSocialUserId: Id[SocialUserInfo],
  state: State[Invitation] = InvitationStates.ACTIVE
) extends ModelWithExternalId[Invitation] {
  def withId(id: Id[Invitation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[Invitation]) = copy(state = state)
}

object InvitationStates extends States[Invitation] {
  val ACCEPTED = State[Invitation]("accepted")
  val ADMIN_REJECTED = State[Invitation]("admin_rejected")
  val ADMIN_ACCEPTED = State[Invitation]("admin_accepted")
  val JOINED = State[Invitation]("joined")
}
