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

  private val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose

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

  def createOrganization() = AdminUserPage { request =>
    val name: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0)
    log.info(s"[RPB] Creating an organization with name '$name'")
    val response = orgCommander.createOrganization(OrganizationCreateRequest(requesterId = fakeOwnerId, initialValues = OrganizationModifications(name = name)))
    Ok(response.toString)
  }
  def addCandidate(orgId: Id[Organization]) = AdminUserPage { request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("candidate-id").head.toLong)
    organizationMembershipCandidateCommander.addCandidates(orgId, Set(userId))
    Ok
  }
  def setName(orgId: Id[Organization]) = AdminUserPage { request =>
    val name: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0)
    log.info(s"[RPB] Unsafely setting organization $orgId to have name '$name'")
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(name = name))
    Ok
  }
  def setDescription(orgId: Id[Organization]) = AdminUserPage { request =>
    val description: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("description").flatMap(_.headOption)).filter(_.length > 0)
    log.info(s"[RPB] Unsafely setting organization $orgId to have description '$description'")
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(description = description))
    Ok
  }
}
