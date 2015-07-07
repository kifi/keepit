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
    val kifiCreateResponse = orgCommander.createOrganization(OrganizationCreateRequest(ryan.id.get, OrganizationModifications(name = Some("Kifi"), description = Some("Knowledge Sharing"))))
    val bcCreateResponse = orgCommander.createOrganization(OrganizationCreateRequest(ryan.id.get, OrganizationModifications(name = Some("Brewster Corp"), description = Some("Taking over the world!"))))
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

  def setName(orgId: Id[Organization]) = AdminUserPage { request =>
    val name: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0)
    log.info(s"[RPB] Unsafely setting organization $orgId to have name '$name'")
    orgCommander.unsafeModifyOrganization(orgId, OrganizationModifications(name = name))
    Ok
  }

  def setDescription(orgId: Id[Organization]) = AdminUserPage { request =>
    val description: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("description").flatMap(_.headOption)).filter(_.length > 0)
    log.info(s"[RPB] Unsafely setting organization $orgId to have description '$description'")
    orgCommander.unsafeModifyOrganization(orgId, OrganizationModifications(description = description))
    Ok
  }
}
