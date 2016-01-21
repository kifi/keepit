package com.keepit.model

import java.math.{ MathContext, RoundingMode, BigDecimal }

import com.google.inject.Injector
import com.keepit.commanders.HandleCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.time._
import com.keepit.model.OrganizationFactory.PartialOrganization
import com.keepit.payments._
import com.keepit.model.PaidPlanFactoryHelper._
import org.joda.time.{ Days, DateTime }

object OrganizationFactoryHelper {
  implicit class OrganizationPersister(partialOrganization: PartialOrganization) {
    def saved(implicit injector: Injector, session: RWSession): Organization = {
      val plan = partialOrganization.planOpt.map { id =>
        injector.getInstance(classOf[PaidPlanRepo]).get(Id[PaidPlan](id))
      }.getOrElse(PaidPlanFactory.paidPlan().saved)
      val orgTemplate = injector.getInstance(classOf[OrganizationRepo]).save(partialOrganization.org.copy(id = None))
      val handleCommander = injector.getInstance(classOf[HandleCommander])
      val org = if (orgTemplate.primaryHandle.isEmpty) {
        handleCommander.autoSetOrganizationHandle(orgTemplate).get
      } else {
        handleCommander.setOrganizationHandle(orgTemplate, orgTemplate.handle, overrideValidityCheck = true).get
      }

      injector.getInstance(classOf[PlanManagementCommander]).createAndInitializePaidAccountForOrganization(orgTemplate.id.get, plan.id.get, org.ownerId, session)
      val initialSettings = plan.defaultSettings.setAll(partialOrganization.nonstandardSettings)
      injector.getInstance(classOf[OrganizationConfigurationRepo]).save(OrganizationConfiguration(organizationId = orgTemplate.id.get, settings = initialSettings))

      val userRepo = injector.getInstance(classOf[UserRepo])
      assume(userRepo.get(org.ownerId).id.isDefined)
      val orgMemRepo = injector.getInstance(classOf[OrganizationMembershipRepo])
      orgMemRepo.save(OrganizationMembership(organizationId = org.id.get, userId = org.ownerId, role = OrganizationRole.ADMIN))
      for (admin <- partialOrganization.admins) {
        orgMemRepo.save(OrganizationMembership(organizationId = org.id.get, userId = admin.id.get, role = OrganizationRole.ADMIN))
        injector.getInstance(classOf[PlanManagementCommanderImpl]).registerNewUser(org.id.get, admin.id.get, OrganizationRole.ADMIN, ActionAttribution(Some(org.ownerId), None))
      }

      for (member <- partialOrganization.members) {
        orgMemRepo.save(OrganizationMembership(organizationId = org.id.get, userId = member.id.get, role = OrganizationRole.MEMBER))
        injector.getInstance(classOf[PlanManagementCommanderImpl]).registerNewUser(org.id.get, member.id.get, OrganizationRole.MEMBER, ActionAttribution(Some(org.ownerId), None))
      }
      val libraryRepo = injector.getInstance(classOf[LibraryRepo])
      val libraryMembershipRepo = injector.getInstance(classOf[LibraryMembershipRepo])
      val oglCR = LibraryInitialValues.forOrgGeneralLibrary(org)
      val lib = libraryRepo.save(Library(ownerId = org.ownerId, name = oglCR.name, slug = LibrarySlug(oglCR.slug.get), kind = oglCR.kind.get, visibility = oglCR.visibility, organizationId = Some(org.id.get), memberCount = 1 + partialOrganization.admins.length + partialOrganization.members.length))
      libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = org.ownerId, access = LibraryAccess.OWNER))
      (partialOrganization.admins ++ partialOrganization.members).foreach { member =>
        libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = member.id.get, access = LibraryAccess.READ_WRITE))
      }

      val orgInvRepo = injector.getInstance(classOf[OrganizationInviteRepo])
      for (invitedUser <- partialOrganization.invitedUsers) {
        orgInvRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = org.ownerId, userId = Some(invitedUser.id.get), role = OrganizationRole.MEMBER))
      }
      for (invitedEmail <- partialOrganization.invitedEmails) {
        orgInvRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = org.ownerId, emailAddress = Some(invitedEmail), role = OrganizationRole.MEMBER))
      }

      partialOrganization.domain.foreach(domain => injector.getInstance(classOf[OrganizationDomainOwnershipRepo]).save(OrganizationDomainOwnership(organizationId = org.id.get, normalizedHostname = domain)))

      org
    }
  }

  implicit class OrganizationsPersister(partialOrganizations: Seq[PartialOrganization]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Organization] = {
      partialOrganizations.map(_.saved)
    }
  }
}
