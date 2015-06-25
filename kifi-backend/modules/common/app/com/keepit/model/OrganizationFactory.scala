package com.keepit.model
import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import org.apache.commons.lang3.RandomStringUtils

import scala.util.Random.nextInt

object OrganizationFactory {
  private[this] val idx = new AtomicLong(10000 + System.currentTimeMillis() % 100)

  def organization(): PartialOrganization = {
    new PartialOrganization(Organization(name = RandomStringUtils.random(10), ownerId = Id[User](nextInt(10000)), handle = None))
  }

  def organizations(count: Int): Seq[PartialOrganization] = List.fill(count)(organization())

  case class PartialOrganization private[OrganizationFactory] (
      org: Organization,
      members: Seq[User] = Seq.empty[User],
      invitedUsers: Seq[User] = Seq.empty[User],
      invitedEmails: Seq[EmailAddress] = Seq.empty[EmailAddress]) {

    def withName(newName: String) = this.copy(org = org.withName(newName))
    def withOwner(newOwner: User) = this.copy(org = org.copy(ownerId = newOwner.id.get))
    def withMembers(newMembers: Seq[User]) = this.copy(members = members ++ newMembers)
    def withInvitedUsers(newInvitedUsers: Seq[User]) = this.copy(invitedUsers = invitedUsers ++ newInvitedUsers)
    def withInvitedEmails(newInvitedEmails: Seq[EmailAddress]) = this.copy(invitedEmails = invitedEmails ++ newInvitedEmails)
  }
}
