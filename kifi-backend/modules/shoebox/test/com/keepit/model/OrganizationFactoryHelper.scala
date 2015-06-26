package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.model.OrganizationFactory.PartialOrganization

object OrganizationFactoryHelper {
  implicit class OrganizationPersister(partialOrganization: PartialOrganization) {
    def saved(implicit injector: Injector, session: RWSession): Organization = {
      val org = injector.getInstance(classOf[OrganizationRepo]).save(partialOrganization.org.copy(id = None))
      injector.getInstance(classOf[OrganizationMembershipRepo]).save(org.newMembership(org.ownerId, OrganizationRole.OWNER))
      for (member <- partialOrganization.members) {
        injector.getInstance(classOf[OrganizationMembershipRepo]).save(org.newMembership(member.id.get, OrganizationRole.MEMBER))
      }
      for (invitedUser <- partialOrganization.invitedUsers) {
        injector.getInstance(classOf[OrganizationInviteRepo]).save(OrganizationInvite(organizationId = org.id.get, inviterId = org.ownerId, userId = Some(invitedUser.id.get), role = OrganizationRole.MEMBER))

      }
      for (invitedEmail <- partialOrganization.invitedEmails) {
        injector.getInstance(classOf[OrganizationInviteRepo]).save(OrganizationInvite(organizationId = org.id.get, inviterId = org.ownerId, emailAddress = Some(invitedEmail), role = OrganizationRole.MEMBER))
      }
      org
    }
  }

  implicit class OrganizationsPersister(partialOrganizations: Seq[PartialOrganization]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Organization] = {
      partialOrganizations.map(_.saved)
    }
  }
}
