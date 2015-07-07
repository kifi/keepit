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

  def populateDatabase() = AdminUserPage { implicit request =>
    val ryan = userCommander.createUser("Ryan", "Brewster", addrOpt = None, state = UserStates.ACTIVE)
    val users = for (i <- 1 to 20) yield {
      userCommander.createUser(i.toString, "TestUser", addrOpt = None, state = UserStates.ACTIVE)
    }
    val kifiCreateResponse = orgCommander.createOrganization(OrganizationCreateRequest(ryan.id.get, OrganizationModifications(name = Some("Kifi"))))
    val bcCreateResponse = orgCommander.createOrganization(OrganizationCreateRequest(ryan.id.get, OrganizationModifications(name = Some("Brewster Corp"))))
    val kifi = kifiCreateResponse.right.get.newOrg
    organizationMembershipCandidateCommander.addCandidates(kifi.id.get, users.map(_.id.get).toSet)
    Ok("done")
  }

  def organizationsView = AdminUserPage { implicit request =>
    val orgIds = orgCommander.getAllOrganizationIds
    val cards = orgCommander.getAnalyticsCards(orgIds)
    val candidateInfos = organizationMembershipCandidateCommander.getCandidatesInfos(orgIds)
    Ok(html.admin.organizations(cards, candidateInfos))
  }
  def organizationViewById(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val orgView = orgCommander.getAnalyticsView(orgId)
    val candidateInfo = organizationMembershipCandidateCommander.getCandidatesInfo(orgId)
    Ok(html.admin.organization(orgView, candidateInfo))
  }

}
