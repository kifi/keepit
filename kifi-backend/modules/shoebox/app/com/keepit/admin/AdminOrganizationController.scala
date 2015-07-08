package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.commanders._
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model._
import views.html

import scala.util.{ Failure, Success }

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
    orgMembershipCandidateCommander: OrganizationMembershipCandidateCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions {

  private val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose

  def organizationsView = AdminUserPage { implicit request =>
    val orgIds = orgCommander.getAllOrganizationIds
    val cards = orgCommander.getAnalyticsCards(orgIds)
    val candidatesInfos = orgMembershipCandidateCommander.getCandidatesInfos(orgIds)
    Ok(html.admin.organizations(cards, fakeOwnerId, candidatesInfos))
  }
  def organizationViewById(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val orgAnalyticsView = orgCommander.getAnalyticsView(orgId)
    val candidateInfo = orgMembershipCandidateCommander.getCandidatesInfo(orgId)
    val ownerId = db.readOnlyMaster { implicit session => orgRepo.get(orgId).ownerId }
    Ok(html.admin.organization(orgAnalyticsView, orgId, ownerId, candidateInfo))
  }

  def createOrganization() = AdminUserPage { request =>
    val ownerId = Id[User](request.body.asFormUrlEncoded.get.apply("owner-id").head.toLong)
    val name = request.body.asFormUrlEncoded.get.apply("name").head
    val response = orgCommander.createOrganization(OrganizationCreateRequest(requesterId = ownerId, initialValues = OrganizationInitialValues(name = name)))
    Ok(response.toString)
  }
  def addCandidate(orgId: Id[Organization]) = AdminUserPage { request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("candidate-id").head.toLong)
    orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
    Ok
  }
  def setName(orgId: Id[Organization]) = AdminUserPage { request =>
    val name: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0)
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(name = name))
    Ok
  }
  def setDescription(orgId: Id[Organization]) = AdminUserPage { request =>
    val description: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("description").flatMap(_.headOption)).filter(_.length > 0)
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(description = description))
    Ok
  }
}
