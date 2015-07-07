package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model._
import views.html

class AdminOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userRepo: UserRepo,
    userCommander: UserCommander,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    organizationMembershipCandidateCommander: OrganizationMembershipCandidateCommander,
    organizationInviteCommander: OrganizationInviteCommander) extends AdminUserActions {

  def populateGarbage() = AdminUserPage { implicit request =>
    val ryan = userCommander.createUser("Ryan", "Brewster", addrOpt = None, state = UserStates.ACTIVE)
    val user2 = userCommander.createUser("Test", "User", addrOpt = None, state = UserStates.ACTIVE)
    val createResponse1 = orgCommander.createOrganization(OrganizationCreateRequest(ryan.id.get, OrganizationModifications(name = Some("Kifi"))))
    val createResponse2 = orgCommander.createOrganization(OrganizationCreateRequest(ryan.id.get, OrganizationModifications(name = Some("Brewster Corp"))))
    val kifi = createResponse1.right.get.newOrg
    organizationMembershipCandidateCommander.addCandidates(kifi.id.get, Set(user2.id.get))
    log.info("made org: " + kifi)
    log.info("made org: " + createResponse2.right.get.newOrg)
    Ok(kifi.toString)
  }

  def organizationViewById(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val (org, members, candidateMembers) = db.readOnlyMaster { implicit session =>
      val org = orgRepo.get(orgId)
      val members = orgMembershipRepo.getAllByOrgId(orgId)
      val candidateMembers = orgMembershipCandidateRepo.getAllByOrgId(orgId)
      (org, members, candidateMembers)
    }
    Ok(html.admin.organization(org, members, candidateMembers))
  }

}
