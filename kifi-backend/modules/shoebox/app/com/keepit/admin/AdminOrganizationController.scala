package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.core.futureExtensionOps
import com.keepit.commanders._
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.model._
import views.html

import scala.concurrent.{ ExecutionContext, Future }

class AdminOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    db: Database,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgMembershipCandidateCommander: OrganizationMembershipCandidateCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    handleCommander: HandleCommander,
    statsCommander: UserStatisticsCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions {

  private val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose

  def organizationsView = AdminUserPage { implicit request =>
    val orgsStats = db.readOnlyMaster { implicit session =>
      val orgIds = orgRepo.all.map(_.id.get)
      orgIds.map { orgId => orgId -> statsCommander.organizationStatisticsOverview(orgId) }.toMap
    }

    Ok(html.admin.organizations(orgsStats, fakeOwnerId))
  }
  def organizationViewById(orgId: Id[Organization]) = AdminUserPage.async { implicit request =>
    val numMemberRecommendations = request.body.asFormUrlEncoded.flatMap(_.get("numMemberRecos").map(_.head.toInt)).getOrElse(20)
    val adminId = request.userId
    val orgStats = db.readOnlyMaster { implicit session => statsCommander.organizationStatistics(orgId, adminId, numMemberRecommendations) }
    orgStats.map { os => Ok(html.admin.organization(os)) }
  }

  def createOrganization() = AdminUserPage { request =>
    val ownerId = Id[User](request.body.asFormUrlEncoded.get.apply("owner-id").head.toLong)
    val name = request.body.asFormUrlEncoded.get.apply("name").head
    val response = orgCommander.createOrganization(OrganizationCreateRequest(requesterId = ownerId, initialValues = OrganizationInitialValues(name = name)))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView)
  }
  def addCandidate(orgId: Id[Organization]) = AdminUserPage { request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("candidate-id").head.toLong)
    orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }
  def addMember(orgId: Id[Organization]) = AdminUserPage { request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("member-id").head.toLong)
    orgMembershipCommander.addMembership(OrganizationMembershipAddRequest(orgId, fakeOwnerId, userId, OrganizationRole.MEMBER))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }

  def setName(orgId: Id[Organization]) = AdminUserPage { request =>
    val name: String = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0).get
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(name = Some(name)))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }
  def setHandle(orgId: Id[Organization]) = AdminUserPage { request =>
    val handle = OrganizationHandle(request.body.asFormUrlEncoded.flatMap(_.get("handle").flatMap(_.headOption)).filter(_.length > 0).get)
    db.readWrite { implicit session =>
      handleCommander.setOrganizationHandle(orgRepo.get(orgId), handle)
    }
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }
  def setDescription(orgId: Id[Organization]) = AdminUserPage { request =>
    val description: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("description").flatMap(_.headOption)).filter(_.length > 0)
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(description = description))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }
}
