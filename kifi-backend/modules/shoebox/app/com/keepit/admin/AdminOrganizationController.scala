package com.keepit.controllers.admin

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Inject
import com.keepit.classify.Domain
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
import play.api.mvc.{ Action, AnyContent, Result }
import views.html

import scala.concurrent.{ ExecutionContext, Future }
import com.keepit.common.time._

import scala.io.Source

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
    orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
    orgDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    handleCommander: HandleCommander,
    statsCommander: UserStatisticsCommander,
    orgExperimentRepo: OrganizationExperimentRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions with PaginationActions {

  private val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose
  private val pageSize = 30

  // needed to coerce the passed in Int => Call to Int => Html
  def asPlayHtml(obj: Any) = HtmlFormat.raw(obj.toString)

  private def getOrgs(page: Int, orgFilter: Organization => Boolean = _ => true): Future[(Int, Seq[OrganizationStatisticsOverview])] = {
    val all = db.readOnlyReplica { implicit s =>
      orgRepo.allActive
    }
    val filteredOrgs = all.filter(orgFilter).sortBy(_.id.get)(Ordering[Id[Organization]].reverse)
    val orgsCount = filteredOrgs.length
    val startingIndex = page * pageSize
    val orgsPage = filteredOrgs.slice(startingIndex, startingIndex + pageSize)
    db.readOnlyReplica { implicit s =>
      Future.sequence(orgsPage.map(org => statsCommander.organizationStatisticsOverview(org)))
    }.map { orgsStats =>
      (orgsCount, orgsStats)
    }
  }

  def organizationsView(page: Int) = AdminUserPage.async { implicit request =>
    getOrgs(page).map {
      case (count, orgs) =>
        Ok(html.admin.organizations(
          orgs,
          "Organizations overall",
          fakeOwnerId,
          (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml),
          page,
          count,
          pageSize
        ))
    }
  }

  def realOrganizationsView(page: Int) = AdminUserPage.async { implicit request =>
    getOrgs(page, org => !orgCommander.hasFakeExperiment(org.id.get)).map {
      case (count, orgs) =>
        Ok(html.admin.organizations(
          orgs,
          "Real Organizations",
          fakeOwnerId,
          (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml),
          page,
          count,
          pageSize
        ))
    }
  }

  def fakeOrganizationsView(page: Int) = AdminUserPage.async { implicit request =>
    getOrgs(page, org => orgCommander.hasFakeExperiment(org.id.get)).map {
      case (count, orgs) =>
        Ok(html.admin.organizations(
          orgs,
          "Fake organizations",
          fakeOwnerId,
          (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml),
          page,
          count,
          pageSize
        ))
    }
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
    val existingOrg = db.readOnlyMaster { implicit session => orgRepo.getOrgByName(name) }
    if (existingOrg.isEmpty) {
      orgCommander.createOrganization(OrganizationCreateRequest(requesterId = ownerId, initialValues = OrganizationInitialValues(name = name))) match {
        case Left(fail) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0))
        case Right(success) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(
          success.newOrg.id.get
        ))
      }
    } else {
      Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(existingOrg.get.id.get))
    }
  }

  def importOrgsFromLinkedIn() = Action { implicit request =>
    val allLines = request.body.asText.get
    try {
      val users = new AtomicInteger(0)
      val createdOrgs = new AtomicInteger(0)
      val existedOrgs = new AtomicInteger(0)
      val createdCand = new AtomicInteger(0)
      val activatedCand = new AtomicInteger(0)
      val existedCand = new AtomicInteger(0)
      allLines.split("\\r?\\n") foreach { line =>
        try {
          val args = line.split(",").map(_.trim).filter(arg => arg.matches("""[\s]*?"[\s]*"[\s]*""")).filter(_.isEmpty)
          if (args.size < 2) {
            throw new Exception(s"less then two args: $args")
          }
          val userId = Id[User](args.head.toLong)
          val orgNames = args.drop(1)
          db.readWrite { implicit s =>
            val user = userRepo.get(userId) //just to make sure we can...
            users.incrementAndGet()
            orgNames.foreach { orgName =>
              val org = orgRepo.getOrgByName(orgName) match {
                case Some(org) =>
                  existedOrgs.incrementAndGet()
                  org
                case None =>
                  orgCommander.createOrganization(OrganizationCreateRequest(requesterId = fakeOwnerId, initialValues = OrganizationInitialValues(name = orgName))) match {
                    case Left(fail) =>
                      throw new Exception(s"failed creating org $orgName for user $user: $fail")
                    case Right(success) =>
                      createdOrgs.incrementAndGet()
                      log.info(success.toString)
                      success.newOrg
                  }
              }
              orgMembershipCandidateRepo.getByUserAndOrg(userId, org.id.get) match {
                case Some(cand) if cand.state == OrganizationMembershipCandidateStates.INACTIVE =>
                  orgMembershipCandidateRepo.save(cand.copy(state = OrganizationMembershipCandidateStates.ACTIVE))
                  activatedCand.incrementAndGet()
                case Some(cand) =>
                  existedCand.incrementAndGet()
                case None =>
                  orgMembershipCandidateRepo.save(OrganizationMembershipCandidate(orgId = org.id.get, userId = userId))
              }
            }
          }
        } catch {
          case e: Exception => throw new Exception(s"error on line: $line", e)
        }
      }
      Ok(s"for ${users.get} users: created ${createdOrgs.get} orgs, reviewed ${existedOrgs.get}, created ${createdCand.get} connections, activated ${activatedCand.get} connections and reviewed ${existedCand.get} connections")
    } catch {
      case e: Exception => InternalServerError(e.toString)
    }
  }

  def findOrganizationByName(orgName: String) = AdminUserPage.async { implicit request =>
    val orgs = db.readOnlyReplica { implicit session =>
      orgRepo.searchOrgsByNameFuzzy(orgName).sortBy(_.id.get)(Ordering[Id[Organization]].reverse)
    }
    if (orgs.isEmpty) {
      Future.successful(Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0)).flashing(
        "error" -> s"No results for '$orgName' found"
      ))
    } else {
      db.readOnlyReplica { implicit session =>
        Future.sequence(orgs.map(org => statsCommander.organizationStatisticsOverview(org)))
      }.map { orgs =>
        Ok(html.admin.organizations(
          orgs,
          s"Results for '$orgName'",
          fakeOwnerId,
          (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml),
          0,
          orgs.length,
          pageSize
        ))
      }
    }
  }

  def findOrganizationByNameJson(orgName: String) = AdminUserPage { implicit request =>
    val orgs = db.readOnlyReplica { implicit session =>
      orgRepo.searchOrgsByNameFuzzy(orgName).sortBy(_.id.get)(Ordering[Id[Organization]].reverse)
    }
    Ok(Json.toJson(orgs))
  }

  def addCandidateOrCreateByName(userId: Id[User]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val orgName = request.body.get("orgName").flatMap(_.headOption).get
    val orgOpt = db.readOnlyReplica { implicit session =>
      orgRepo.getOrgByName(orgName)
    }
    orgOpt match {
      case Some(org) =>
        val orgId = org.id.get
        orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
      case None =>
        orgCommander.createOrganization(OrganizationCreateRequest(requesterId = fakeOwnerId, initialValues = OrganizationInitialValues(name = orgName))) match {
          case Left(fail) => NotFound
          case Right(success) =>
            val orgId = success.newOrg.id.get
            orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
            Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
        }
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

  def addDomainOwnership(orgId: Id[Organization]) = AdminUserAction(parse.tolerantFormUrlEncoded) { implicit request =>
    val body = request.body
    val domainName = body.get("domainName").flatMap(_.headOption).get
    val orgView = com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId)
    orgDomainOwnershipCommander.addDomainOwnership(orgId, domainName) match {
      case Left(failure) =>
        Redirect(orgView).flashing(
          "error" -> failure.humanString
        )
      case Right(success) =>
        Redirect(orgView)
    }
  }

  def removeDomainOwnership(orgId: Id[Organization], domainId: Id[Domain]) = AdminUserAction { implicit request =>
    orgDomainOwnershipCommander.removeDomainOwnership(orgId, domainId)
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewById(orgId))
  }

  def setInactive(orgId: Id[Organization]) = AdminUserAction { implicit request =>
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val orgInactive = org.copy(state = OrganizationStates.INACTIVE)
      orgRepo.save(orgInactive)
    }
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0))
  }

}
