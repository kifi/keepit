package com.keepit.model

import com.keepit.common.crypto.PublicId
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class OrganizationMembershipPoke(
    id: Option[Id[OrganizationMembershipPoke]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationMembershipPoke] = OrganizationMembershipPokeStates.ACTIVE,
    organizationId: Id[Organization],
    userId: Id[User]) extends ModelWithState[OrganizationMembershipPoke] {

  def withId(id: Id[OrganizationMembershipPoke]): OrganizationMembershipPoke = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationMembershipPoke = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationMembershipPoke]): OrganizationMembershipPoke = this.copy(state = newState)
  def sanitizeForDelete: OrganizationMembershipPoke = this.copy(
    state = OrganizationMembershipPokeStates.INACTIVE
  )
}

object OrganizationMembershipPokeStates extends States[OrganizationMembershipPoke]

case class ExternalOrganizationMembershipPoke(
  createdAt: DateTime = currentDateTime,
  organizationId: PublicId[Organization],
  userId: ExternalId[User])

object ExternalOrganizationMembershipPoke {
  implicit val writes: Writes[ExternalOrganizationMembershipPoke] = (
    (__ \ 'lastPoked).write[DateTime] and
    (__ \ 'organizationId).write[PublicId[Organization]] and
    (__ \ 'userId).write[ExternalId[User]]
  )(unlift(ExternalOrganizationMembershipPoke.unapply))
}
