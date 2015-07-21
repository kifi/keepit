package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.core.futureExtensionOps
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model._
import play.api.libs.json.Json
import play.twirl.api.{ HtmlFormat, Html }
import views.html

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.time._

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
    orgExperimentRepo: OrganizationExperimentRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions with PaginationActions {

  private val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose

  // needed to coerce the passed in Int => Call to Int => Html
  def asPlayHtml(obj: Any) = HtmlFormat.raw(obj.toString)

  def organizationsView(page: Int) = AdminUserPage.async { implicit authenticated =>
    val orgsStats = db.readOnlyReplica { implicit session =>
      orgRepo.all.map(org => statsCommander.organizationStatisticsOverview(org))
    }.sortBy(_.orgId)(Ordering[Id[Organization]].reverse)

    val orgsStatsGrouped = orgsStats.grouped(30).toSeq
    val paginatedOrgStats = if (orgsStatsGrouped.isEmpty) List(List()) else orgsStatsGrouped

    PaginatedPage[OrganizationStatisticsOverview](orgsStats.length, paginatedOrgStats.apply)(page) { implicit paginated =>
      Ok(html.admin.organizations(paginated.items, "Organizations overall", fakeOwnerId, (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml)))
    }(authenticated)

  }

  def realOrganizationsView(page: Int) = AdminUserPage.async { implicit authenticated =>
    val orgsStats = db.readOnlyReplica { implicit session =>
      orgRepo.all.map(org => statsCommander.organizationStatisticsOverview(org)).filter(org => !orgCommander.hasFakeExperiment(org.orgId)).sortBy(_.orgId)((Ordering[Id[Organization]].reverse))
    }

    val orgsStatsGrouped = orgsStats.grouped(30).toSeq
    val paginatedOrgStats = if (orgsStatsGrouped.isEmpty) List(List()) else orgsStatsGrouped

    PaginatedPage[OrganizationStatisticsOverview](orgsStats.length, paginatedOrgStats.apply)(page) { implicit paginated =>
      Ok(html.admin.organizations(paginated.items, "Real organizations", fakeOwnerId, (com.keepit.controllers.admin.routes.AdminOrganizationController.realOrganizationsView _).andThen(asPlayHtml)))
    }(authenticated)
  }

  def fakeOrganizationsView(page: Int) = AdminUserPage.async { implicit authenticated =>
    val orgsStats = db.readOnlyReplica { implicit session =>
      orgRepo.all.map(org => statsCommander.organizationStatisticsOverview(org)).filter(org => orgCommander.hasFakeExperiment(org.orgId)).sortBy(_.orgId)((Ordering[Id[Organization]].reverse))
    }

    val orgsStatsGrouped = orgsStats.grouped(30).toSeq
    val paginatedOrgStats = if (orgsStatsGrouped.isEmpty) List(List()) else orgsStatsGrouped

    PaginatedPage[OrganizationStatisticsOverview](orgsStats.length, paginatedOrgStats.apply)(page) { implicit paginated =>
      Ok(html.admin.organizations(paginated.items, "Fake organizations", fakeOwnerId, (com.keepit.controllers.admin.routes.AdminOrganizationController.fakeOrganizationsView _).andThen(asPlayHtml)))
    }(authenticated)
  }

  def organizationViewById(orgId: Id[Organization]) = AdminUserPage.async { implicit request =>
    val numMemberRecommendations = request.queryString.get("numMemberRecos").map(_.head.toInt).getOrElse(30)
    val adminId = request.userId
    val orgStats = statsCommander.organizationStatistics(orgId, adminId, numMemberRecommendations)
    orgStats.map { os => Ok(html.admin.organization(os)) }
  }

  def createOrganization() = AdminUserPage { implicit request =>
    val ownerId = Id[User](request.body.asFormUrlEncoded.get.apply("owner-id").head.toLong)
    val name = request.body.asFormUrlEncoded.get.apply("name").head
    val existingOrg = db.readOnlyMaster { implicit session => orgRepo.getOrganizationsByName(name) }
    if (existingOrg.isEmpty) {
      orgCommander.createOrganization(OrganizationCreateRequest(requesterId = ownerId, initialValues = OrganizationInitialValues(name = name))) match {
        case Left(fail) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0))
        case Right(success) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(
          success.newOrg.id.get
        ))
      }
    } else {
      Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(existingOrg.head.id.get))
    }
  }

  def findOrganizationByName(orgName: String) = AdminUserPage.async { implicit request =>
    val orgs = db.readOnlyReplica { implicit session =>
      orgRepo.getOrganizationsByName(orgName).sortBy(_.id.get)(Ordering[Id[Organization]].reverse)
    }
    if (orgs.isEmpty) {
      Future.successful(Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0)).flashing(
        "error" -> s"Organization '$orgName' not found"
      ))
    } else {
      val orgsStats = db.readOnlyReplica { implicit session =>
        orgs.map(org => statsCommander.organizationStatisticsOverview(org))
      }
      PaginatedPage[OrganizationStatisticsOverview](orgsStats.length, (page: Int) => orgsStats)(0) { implicit paginated =>
        Ok(html.admin.organizations(paginated.items, s"organizations matching '$orgName'", fakeOwnerId, (com.keepit.controllers.admin.routes.AdminOrganizationController.fakeOrganizationsView _).andThen(asPlayHtml)))
      }(request)
    }
  }

  def addCandidateOrCreateByName(userId: Id[User]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val orgName = request.body.get("orgName").flatMap(_.headOption).get
    val orgsByName = db.readOnlyReplica { implicit session =>
      orgRepo.getOrganizationsByName(orgName)
    }
    orgsByName match {
      case Seq() =>
        orgCommander.createOrganization(OrganizationCreateRequest(requesterId = fakeOwnerId, initialValues = OrganizationInitialValues(name = orgName))) match {
          case Left(fail) => NotFound
          case Right(success) =>
            val orgId = success.newOrg.id.get
            orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
            Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
        }
      case Seq(org) =>
        val orgId = org.id.get
        orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
      case orgs =>
        val orgId = orgs.head.id.get
        orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId)).flashing(
          "warning" -> s"Other organizations with this name were found, please be careful that this is the right org"
        )
    }
  }

  def addCandidate(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("candidate-id").head.toLong)
    orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }

  def removeCandidate(orgId: Id[Organization]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val userId = Id[User](request.body.get("candidate-id").flatMap(_.headOption).get.toLong)
    orgMembershipCandidateCommander.removeCandidates(orgId, Set(userId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }

  def addMember(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("member-id").head.toLong)
    orgMembershipCommander.addMembership(OrganizationMembershipAddRequest(orgId, fakeOwnerId, userId, OrganizationRole.MEMBER))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }

  def setName(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val name: String = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0).get
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(name = Some(name)))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }

  def setHandle(orgId: Id[Organization]) = AdminUserPage { implicit request =>
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

  def addExperimentAction(orgId: Id[Organization], experiment: String) = AdminUserAction { request =>
    addExperiment(requesterUserId = request.userId, orgId, experiment) match {
      case Right(expType) => Ok(Json.obj(experiment -> true))
      case Left(s) => Forbidden
    }
  }

  def addExperiment(requesterUserId: Id[User], orgId: Id[Organization], experiment: String): Either[String, OrganizationExperimentType] = {
    val expType = OrganizationExperimentType.get(experiment)
    db.readWrite { implicit session =>
      orgExperimentRepo.getByOrganizationIdAndExperimentType(orgId, expType, excludeStates = Set()) match {
        case Some(oe) if oe.isActive => None
        case Some(oe) => Some(orgExperimentRepo.save(oe.withState(OrganizationExperimentStates.ACTIVE)))
        case None => Some(orgExperimentRepo.save(OrganizationExperiment(orgId = orgId, experimentType = expType)))
      }
      Right(expType)
    }
  }

  def removeExperimentAction(orgId: Id[Organization], experiment: String) = AdminUserAction { request =>
    removeExperiment(requesterUserId = request.userId, orgId, experiment) match {
      case Right(expType) => Ok(Json.obj(experiment -> false))
      case Left(s) => Forbidden
    }
  }

  def removeExperiment(requesterUserId: Id[User], orgId: Id[Organization], experiment: String): Either[String, OrganizationExperimentType] = {
    val expType = OrganizationExperimentType(experiment)
    db.readWrite { implicit session =>
      orgExperimentRepo.getByOrganizationIdAndExperimentType(orgId, OrganizationExperimentType(experiment)) foreach { orgExperimentRepo.deactivate }
    }
    Right(expType)
  }
}
