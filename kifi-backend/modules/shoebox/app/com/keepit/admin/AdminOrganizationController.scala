package com.keepit.controllers.admin

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Inject
import com.keepit.common.core.futureExtensionOps
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.json.KeyFormat
import com.keepit.heimdal.{ HeimdalContextBuilder, HeimdalContext }
import com.keepit.model._
import play.api.{ Mode, Play }
import play.api.libs.json.{ JsString, Json }
import play.twirl.api.{ HtmlFormat, Html }
import play.api.mvc.{ Action, AnyContent, Result }
import views.html

import scala.concurrent.{ Promise, ExecutionContext, Future }
import com.keepit.common.time._

import scala.util.Try

object AdminOrganizationController {
  val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose
}

class AdminOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    db: Database,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    libRepo: LibraryRepo,
    userExperimentRepo: UserExperimentRepo,
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

  val fakeOwnerId = if (Play.maybeApplication.exists(_.mode == Mode.Prod)) AdminOrganizationController.fakeOwnerId else Id[User](1)
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
    Future.sequence(orgsPage.map(org => statsCommander.organizationStatisticsOverview(org))).map { orgsStats =>
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

  def liveOrganizationsView() = AdminUserPage.async { implicit request =>
    val orgs = db.readOnlyReplica { implicit s =>
      val orgIds = libRepo.orgsWithMostLibs().map(_._1)
      val allOrgs = orgRepo.getByIds(orgIds.toSet)
      orgIds.map(id => allOrgs(id))
    }
    Future.sequence(orgs.map(org => statsCommander.organizationStatisticsOverview(org))).map { orgStats =>
      Ok(html.admin.organizations(
        orgStats,
        "Top Live Organizations",
        fakeOwnerId,
        (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml),
        1,
        orgs.size,
        pageSize
      ))
    }
  }

  private def isFake(org: Organization): Boolean = db.readOnlyMaster { implicit session =>
    orgExperimentRepo.hasExperiment(org.id.get, OrganizationExperimentType.FAKE)
  }

  def realOrganizationsView(page: Int) = AdminUserPage.async { implicit request =>
    getOrgs(page, !isFake(_)).map {
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
    getOrgs(page, !isFake(_)).map {
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

  def organizationViewBy(orgId: Id[Organization], numMemberRecos: Int = 60) = AdminUserPage.async { implicit request =>
    val adminId = request.userId
    organizationViewById(orgId, numMemberRecos, adminId)
  }

  def organizationViewById(orgId: Id[Organization], numMemberRecos: Int, adminId: Id[User])(implicit request: UserRequest[AnyContent]) = {
    val orgStats = statsCommander.organizationStatistics(orgId, adminId, numMemberRecos)
    orgStats.map { os => Ok(html.admin.organization(os)) }
  }

  def organizationViewByEitherId(orgIdStr: String, numMemberRecos: Int = 60) = AdminUserPage.async { implicit request =>
    val adminId = request.userId
    val orgId = Try(orgIdStr.toLong).toOption map { orgId =>
      Id[Organization](orgId)
    } getOrElse {
      Organization.decodePublicId(PublicId[Organization](orgIdStr)).get
    }
    organizationViewById(orgId, numMemberRecos, adminId)
  }

  def createOrganization() = AdminUserPage { implicit request =>
    implicit val context = HeimdalContext.empty
    val ownerId = Id[User](request.body.asFormUrlEncoded.get.apply("owner-id").head.toLong)
    val name = request.body.asFormUrlEncoded.get.apply("name").head
    val existingOrg = db.readOnlyMaster { implicit session => orgRepo.getOrgByName(name) }
    if (existingOrg.isEmpty) {
      orgCommander.createOrganization(OrganizationCreateRequest(requesterId = ownerId, initialValues = OrganizationInitialValues(name = name))) match {
        case Left(fail) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0))
        case Right(success) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(
          success.newOrg.id.get
        ))
      }
    } else {
      Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(existingOrg.get.id.get))
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
          val args = line.split(",").map(_.trim).filterNot(arg => arg.matches("""[\s]*?"[\s]*"[\s]*""")).filterNot(_.isEmpty)
          if (args.size < 2) {
            throw new Exception(s"less then two args: ${args.mkString(",")}")
          }
          val userId = Id[User](args.head.toLong)
          val orgNames = args.drop(1)
          db.readWrite { implicit s =>
            val user = userRepo.get(userId) //just to make sure we can...
            users.incrementAndGet()
            orgNames.foreach { orgName =>
              val org = orgRepo.getOrgByName(orgName) match {
                case Some(orgByName) =>
                  existedOrgs.incrementAndGet()
                  orgByName
                case None =>
                  implicit val context = HeimdalContext.empty
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
                  orgMembershipCandidateRepo.save(OrganizationMembershipCandidate(organizationId = org.id.get, userId = userId))
              }
            }
          }
        } catch {
          case e: Exception => throw new Exception(s"error on line: $line $e", e)
        }
      }
      Ok(s"for ${users.get} users: created ${createdOrgs.get} orgs, reviewed ${existedOrgs.get}, created ${createdCand.get} connections, activated ${activatedCand.get} connections and reviewed ${existedCand.get} connections")
    } catch {
      case e: Exception =>
        log.error(allLines, e)
        InternalServerError(e.toString)
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
      Future.sequence(orgs.map(org => statsCommander.organizationStatisticsOverview(org))).map { orgs =>
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
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
      case None =>
        implicit val context = HeimdalContext.empty
        orgCommander.createOrganization(OrganizationCreateRequest(requesterId = fakeOwnerId, initialValues = OrganizationInitialValues(name = orgName))) match {
          case Left(fail) => NotFound
          case Right(success) =>
            val orgId = success.newOrg.id.get
            orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
            Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
        }
    }
  }

  def transferOwner(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val newOwnerId = Id[User](request.body.asFormUrlEncoded.get.apply("user-id").head.toLong)
    val org = db.readOnlyReplica { implicit s => orgRepo.get(orgId) }
    val oldOwnerId = org.ownerId
    implicit val context = HeimdalContext.empty
    orgCommander.transferOrganization(OrganizationTransferRequest(oldOwnerId, orgId, newOwnerId)) match {
      case Left(fail) =>
        fail.asErrorResponse
      case Right(res) =>
        //next two line are to check that the impossible does not happen
        val updatedOrg = db.readOnlyMaster { implicit s => orgRepo.get(orgId) }
        assume(updatedOrg.ownerId == newOwnerId)
        /**
         * When we're creating orgs via the admin tool, we're setting a fake owner id as the owner to preserve data integrity.
         * Once we make a real user own the org there is no longer a need for that fake user and we're taking them out.
         */
        if (oldOwnerId == fakeOwnerId) {
          orgMembershipCommander.removeMembership(OrganizationMembershipRemoveRequest(orgId, requesterId = newOwnerId, targetId = oldOwnerId))
        }
        db.readWrite { implicit s =>
          orgMembershipCandidateRepo.getByUserAndOrg(newOwnerId, orgId) match {
            case Some(candidate) => orgMembershipCandidateRepo.save(candidate.copy(state = OrganizationMembershipCandidateStates.INACTIVE))
            case None => //whatever
          }
        }
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
    }
  }

  def addCandidate(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("user-id").head.toLong)
    orgMembershipCandidateCommander.addCandidates(orgId, Set(userId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def removeMember(orgId: Id[Organization]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val userId = Id[User](request.body.get("user-id").flatMap(_.headOption).get.toLong)
    val org = db.readOnlyReplica { implicit s => orgRepo.get(orgId) }
    orgMembershipCommander.removeMembership(OrganizationMembershipRemoveRequest(orgId, requesterId = org.ownerId, targetId = userId)) match {
      case Right(res) =>
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
      case Left(fail) =>
        fail.asErrorResponse
    }
  }

  def removeCandidate(orgId: Id[Organization]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val userId = Id[User](request.body.get("user-id").flatMap(_.headOption).get.toLong)
    orgMembershipCandidateCommander.removeCandidates(orgId, Set(userId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def addMember(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("user-id").head.toLong)
    val org = db.readOnlyReplica { implicit s => orgRepo.get(orgId) }
    orgMembershipCommander.addMembership(OrganizationMembershipAddRequest(orgId, requesterId = org.ownerId, targetId = userId, OrganizationRole.MEMBER)) match {
      case Right(res) =>
        Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
      case Left(fail) =>
        fail.asErrorResponse
    }
  }

  def inviteCandidateToOrg(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val userId = Id[User](request.body.asFormUrlEncoded.get.apply("user-id").head.toLong)
    orgMembershipCandidateCommander.inviteCandidate(orgId, userId)
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def setName(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val name: String = request.body.asFormUrlEncoded.flatMap(_.get("name").flatMap(_.headOption)).filter(_.length > 0).get
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(name = Some(name)))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def setHandle(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val handle = OrganizationHandle(request.body.asFormUrlEncoded.flatMap(_.get("handle").flatMap(_.headOption)).filter(_.length > 0).get)
    db.readWrite { implicit session =>
      handleCommander.setOrganizationHandle(orgRepo.get(orgId), handle, overrideValidityCheck = true)
    }
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def setDescription(orgId: Id[Organization]) = AdminUserPage { request =>
    val description: Option[String] = request.body.asFormUrlEncoded.flatMap(_.get("description").flatMap(_.headOption)).filter(_.length > 0)
    orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(description = description))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
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

  def addRolePermission(orgId: Id[Organization], roleStr: String, permissionStr: String) = AdminUserAction { request =>
    implicit val context = HeimdalContext.empty
    val roleOpt = if (roleStr == "none") None else Some(OrganizationRole(roleStr))
    val permission = OrganizationPermission(permissionStr)
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val pdiff = PermissionsDiff(added = PermissionsMap(roleOpt -> Set(permission)))
      orgCommander.modifyOrganization(OrganizationModifyRequest(org.ownerId, org.id.get, OrganizationModifications(permissionsDiff = Some(pdiff))))
    } match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => Ok(JsString(s"added $permission to $roleOpt"))
    }
  }
  def removeRolePermission(orgId: Id[Organization], roleStr: String, permissionStr: String) = AdminUserAction { request =>
    implicit val context = HeimdalContext.empty
    val roleOpt = if (roleStr == "none") None else Some(OrganizationRole(roleStr))
    val permission = OrganizationPermission(permissionStr)
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val pdiff = PermissionsDiff(removed = PermissionsMap(roleOpt -> Set(permission)))
      orgCommander.modifyOrganization(OrganizationModifyRequest(org.ownerId, org.id.get, OrganizationModifications(permissionsDiff = Some(pdiff))))
    } match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => Ok(JsString(s"removed $permission from $roleOpt"))
    }
  }

  def addDomainOwnership(orgId: Id[Organization]) = AdminUserAction(parse.tolerantFormUrlEncoded) { implicit request =>
    val body = request.body
    val domainName = body.get("domainName").flatMap(_.headOption).get
    val orgView = com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId)
    orgDomainOwnershipCommander.addDomainOwnership(orgId, domainName) match {
      case Left(failure) =>
        Redirect(orgView).flashing(
          "error" -> failure.humanString
        )
      case Right(success) =>
        Redirect(orgView)
    }
  }

  def removeDomainOwnership(orgId: Id[Organization], domainHostname: String) = AdminUserAction { implicit request =>
    orgDomainOwnershipCommander.removeDomainOwnership(orgId, domainHostname)
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def forceDeactivate(orgId: Id[Organization]) = AdminUserAction { implicit request =>
    implicit val context = HeimdalContext.empty
    val deleteResponse = db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      orgCommander.deleteOrganization(OrganizationDeleteRequest(org.ownerId, org.id.get))
    }
    deleteResponse match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0))
    }
  }

  def addPermissionToAllOrganizations() = AdminUserAction(parse.tolerantJson) { implicit request =>
    implicit val reads = KeyFormat.key2Reads[OrganizationPermission, String]("permission", "confirmation")
    val (newPermission, confirmation) = request.body.as[(OrganizationPermission, String)]
    assert(confirmation == "i swear i know what i am doing", "admin does not know what they are doing")
    val orgIds = db.readOnlyMaster { implicit session => orgRepo.all.filter(_.isActive).map(_.id.get) }
    for (orgId <- orgIds) {
      val pdiff = PermissionsDiff(added = PermissionsMap(OrganizationRole.all.map(Option(_) -> Set(newPermission)).toMap))
      orgCommander.unsafeModifyOrganization(request, orgId, OrganizationModifications(permissionsDiff = Some(pdiff)))
    }
    Ok(Json.obj("added" -> newPermission, "modified" -> orgIds))
  }

}
