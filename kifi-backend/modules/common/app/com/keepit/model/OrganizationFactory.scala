package com.keepit.model
import java.util.concurrent.atomic.AtomicLong

import com.keepit.classify.NormalizedHostname
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import org.apache.commons.lang3.RandomStringUtils

object OrganizationFactory {
  private[this] val idx = new AtomicLong(10000 + System.currentTimeMillis() % 100)

  def organization(): PartialOrganization = {
    new PartialOrganization(Organization(name = RandomStringUtils.random(10), ownerId = Id[User](idx.incrementAndGet()), primaryHandle = None, description = None, site = None))
  }

  def organizations(count: Int): Seq[PartialOrganization] = List.fill(count)(organization())

  case class PartialOrganization private[OrganizationFactory] (
      org: Organization,
      admins: Seq[User] = Seq.empty[User],
      members: Seq[User] = Seq.empty[User],
      invitedUsers: Seq[User] = Seq.empty[User],
      invitedEmails: Seq[EmailAddress] = Seq.empty[EmailAddress],
      nonstandardSettings: Map[Feature, FeatureSetting] = Map.empty,
      // TODO(ryan): this Long is literally my least favorite thing in the world right now
      planOpt: Option[Long] = None,
      domain: Option[NormalizedHostname] = None) {

    def withName(newName: String) = this.copy(org = org.withName(newName))
    def withOwner(newOwner: User) = this.copy(org = org.withOwner(newOwner.id.get))
    def withOwner(newOwner: Id[User]) = this.copy(org = org.withOwner(newOwner))
    def withOwner(newOwner: Int) = this.copy(org = org.withOwner(Id[User](newOwner)))
    def withAdmins(newAdmins: Seq[User]) = this.copy(admins = newAdmins)
    def withMembers(newMembers: Seq[User]) = this.copy(members = newMembers)
    def withInvitedUsers(newInvitedUsers: Seq[User]) = this.copy(invitedUsers = newInvitedUsers)
    def withInvitedEmails(newInvitedEmails: Seq[EmailAddress]) = this.copy(invitedEmails = newInvitedEmails)
    def withHandle(newHandle: OrganizationHandle): PartialOrganization = this.copy(org = org.copy(primaryHandle = Some(PrimaryOrganizationHandle(newHandle, newHandle))))
    def withDomain(domain: String): PartialOrganization = this.copy(domain = Some(NormalizedHostname.fromHostname(domain).get))
    def withSettings(settings: Map[Feature, FeatureSetting]) = this.copy(nonstandardSettings = nonstandardSettings ++ settings)

    // This method makes it so an org's members cannot invite, useful for testing
    def withWeakMembers() = this.copy(nonstandardSettings = nonstandardSettings + (StaticFeature.InviteMembers -> StaticFeatureSetting.ADMINS))
    // admins can force-edit
    def withStrongAdmins() = this.copy(nonstandardSettings = nonstandardSettings + (StaticFeature.ForceEditLibraries -> StaticFeatureSetting.ADMINS))
    def withSlackProhibited() = this.copy(nonstandardSettings = nonstandardSettings + (StaticFeature.CreateSlackIntegration -> StaticFeatureSetting.DISABLED))
    def secret() = this.copy(nonstandardSettings = nonstandardSettings + (StaticFeature.ViewOrganization -> StaticFeatureSetting.MEMBERS) + (StaticFeature.ViewMembers -> StaticFeatureSetting.MEMBERS))

    def withPlan(planId: Long) = this.copy(planOpt = Some(planId))
  }
}
