package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import org.joda.time.DateTime

object OrganizationInviteFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def organizationInvite(): PartialOrganizationInvite = new PartialOrganizationInvite(OrganizationInvite(
    organizationId = Id[Organization](idx.getAndIncrement()),
    inviterId = Id[User](idx.getAndIncrement())
  ))

  def organizationInvites(count: Int): Seq[PartialOrganizationInvite] = List.fill(count)(organizationInvite())

  case class PartialOrganizationInvite private[OrganizationInviteFactory] (orgInvite: OrganizationInvite) {
    def withOrganization(org: Organization) = PartialOrganizationInvite(orgInvite.copy(organizationId = org.id.get))
    def withOrganizationId(orgId: Id[Organization]) = PartialOrganizationInvite(orgInvite.copy(organizationId = orgId))
    def withInviter(inviter: User) = PartialOrganizationInvite(orgInvite.copy(inviterId = inviter.id.get))
    def withUser(user: User) = PartialOrganizationInvite(orgInvite.copy(userId = user.id))
    def withEmailAddress(emailAddress: EmailAddress) = PartialOrganizationInvite(orgInvite.copy(emailAddress = Some(emailAddress)))
    def withDecision(decision: InvitationDecision) = PartialOrganizationInvite(orgInvite.copy(decision = decision))
    def withCreatedAt(date: DateTime) = PartialOrganizationInvite(orgInvite.copy(createdAt = date))
    def get: OrganizationInvite = OrganizationInvite(
      decision = orgInvite.decision,
      organizationId = orgInvite.organizationId,
      inviterId = orgInvite.inviterId,
      userId = orgInvite.userId,
      emailAddress = orgInvite.emailAddress,
      createdAt = orgInvite.createdAt
    )
  }

  implicit class PartialOrganizationInviteSeq(invites: Seq[PartialOrganizationInvite]) {
    def get: Seq[OrganizationInvite] = invites.map(_.get)
  }
}
