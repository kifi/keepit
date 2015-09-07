package com.keepit.model

import com.google.inject.Injector
import com.keepit.commanders.HandleCommander
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.images.RawImageInfo
import com.keepit.common.store.ImagePath
import com.keepit.model.ImageSource.UserUpload
import com.keepit.model.OrganizationFactory.PartialOrganization
import com.keepit.payments._

object OrganizationFactoryHelper {
  implicit class OrganizationPersister(partialOrganization: PartialOrganization) {
    def saved(implicit injector: Injector, session: RWSession): Organization = {
      injector.getInstance(classOf[PlanManagementCommanderImpl]).createNewPlanHelper(Name[PaidPlan]("Test"), BillingCycle(1), DollarAmount(0))
      val orgTemplate = injector.getInstance(classOf[OrganizationRepo]).save(partialOrganization.org.copy(id = None))
      val handleCommander = injector.getInstance(classOf[HandleCommander])
      val org = if (orgTemplate.primaryHandle.isEmpty) {
        handleCommander.autoSetOrganizationHandle(orgTemplate).get
      } else {
        handleCommander.setOrganizationHandle(orgTemplate, orgTemplate.handle, overrideValidityCheck = true).get
      }

      injector.getInstance(classOf[PlanManagementCommander]).createAndInitializePaidAccountForOrganization(orgTemplate.id.get, PaidPlan.DEFAULT, org.ownerId, session)

      val userRepo = injector.getInstance(classOf[UserRepo])
      assume(userRepo.get(org.ownerId).id.isDefined)
      val orgMemRepo = injector.getInstance(classOf[OrganizationMembershipRepo])
      orgMemRepo.save(org.newMembership(org.ownerId, OrganizationRole.ADMIN))
      for (admin <- partialOrganization.admins) {
        orgMemRepo.save(org.newMembership(admin.id.get, OrganizationRole.ADMIN))
      }
      for (member <- partialOrganization.members) {
        orgMemRepo.save(org.newMembership(member.id.get, OrganizationRole.MEMBER))
      }
      val orgInvRepo = injector.getInstance(classOf[OrganizationInviteRepo])
      for (invitedUser <- partialOrganization.invitedUsers) {
        orgInvRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = org.ownerId, userId = Some(invitedUser.id.get), role = OrganizationRole.MEMBER))
      }
      for (invitedEmail <- partialOrganization.invitedEmails) {
        orgInvRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = org.ownerId, emailAddress = Some(invitedEmail), role = OrganizationRole.MEMBER))
      }
      val imageHash = ImageHash("076fccc32247ae67bb75d48879230953")
      val orgAvatarRepo = injector.getInstance(classOf[OrganizationAvatarRepoImpl])
      orgAvatarRepo.save(OrganizationAvatar(organizationId = org.id.get, width = 100, height = 100, format = ImageFormat.JPG, kind = ProcessImageOperation.CropScale, imagePath = ImagePath("oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-100x100_cs.jpg"), source = UserUpload, sourceFileHash = imageHash, sourceImageURL = None))
      orgAvatarRepo.save(OrganizationAvatar(organizationId = org.id.get, width = 200, height = 200, format = ImageFormat.JPG, kind = ProcessImageOperation.CropScale, imagePath = ImagePath("oa/076fccc32247ae67bb75d48879230953_1024x1024-0x0-200x200_cs.jpg"), source = UserUpload, sourceFileHash = imageHash, sourceImageURL = None))
      org
    }
  }

  implicit class OrganizationsPersister(partialOrganizations: Seq[PartialOrganization]) {
    def saved(implicit injector: Injector, session: RWSession): Seq[Organization] = {
      partialOrganizations.map(_.saved)
    }
  }
}
