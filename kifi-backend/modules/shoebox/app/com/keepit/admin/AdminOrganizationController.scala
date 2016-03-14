package com.keepit.controllers.admin

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Inject
import com.keepit.classify.NormalizedHostname
import com.keepit.common.concurrent.{ FutureHelpers, ChunkedResponseHelper }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.core.traversableOnceExtensionOps
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.model._
import com.keepit.payments.{ PaidPlanRepo, PaidAccountRepo }
import com.keepit.slack.models.{ SlackTeamId, SlackMessageRequest, SlackTeamMembership, SlackTeamMembershipRepo, SlackAuthScope, SlackTeamRepo }
import com.keepit.slack.{ SlackClientWrapper, SlackClient }
import play.api.libs.iteratee.Concurrent
import play.api.{ Mode, Play }
import play.api.libs.json.Json
import play.twirl.api.HtmlFormat
import play.api.mvc.{ Action, AnyContent }
import views.html

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Failure, Success, Try }

object AdminOrganizationController {
  val fakeOwnerId = Id[User](97543) // "Fake Owner", a special private Kifi user specifically for this purpose
}

class AdminOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    db: Database,
    userRepo: UserRepo,
    keepRepo: KeepToLibraryRepo,
    orgRepo: OrganizationRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    paidAccountRepo: PaidAccountRepo,
    paidPlanRepo: PaidPlanRepo,
    libRepo: LibraryRepo,
    libCommander: LibraryCommander,
    slackTeamRepo: SlackTeamRepo,
    slackMembershipRepo: SlackTeamMembershipRepo,
    slackClient: SlackClientWrapper,
    userEmailAddressRepo: UserEmailAddressRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgMembershipCandidateCommander: OrganizationMembershipCandidateCommander,
    orgDomainOwnershipCommander: OrganizationDomainOwnershipCommanderImpl,
    handleCommander: HandleCommander,
    userStatsCommander: UserStatisticsCommander,
    orgStatsCommander: OrgStatisticsCommander,
    orgExperimentRepo: OrganizationExperimentRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions with PaginationActions {

  val fakeOwnerId = if (Play.maybeApplication.exists(_.mode == Mode.Prod)) AdminOrganizationController.fakeOwnerId else Id[User](1)
  private val pageSize = 30

  // needed to coerce the passed in Int => Call to Int => Html
  def asPlayHtml(obj: Any) = HtmlFormat.raw(obj.toString)

  private def getOrgs(page: Int, orgFilter: Id[Organization] => Boolean = _ => true): Future[(Int, Seq[OrganizationStatisticsOverview])] = {
    val all = db.readOnlyReplica { implicit s =>
      orgRepo.allActiveIds
    }
    val filteredOrgIds = all.filter(orgFilter).sorted(Ordering[Id[Organization]].reverse)
    val orgsCount = filteredOrgIds.length
    val startingIndex = page * pageSize
    val orgsPage = {
      val orgIdsToFetch = filteredOrgIds.slice(startingIndex, startingIndex + pageSize)
      val orgsById = db.readOnlyReplica { implicit s => orgRepo.getByIds(orgIdsToFetch.toSet) }
      orgIdsToFetch.flatMap(orgsById.get)
    }
    Future.sequence(orgsPage.map(org => orgStatsCommander.organizationStatisticsOverview(org))).map { orgsStats =>
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
      val orgIds = keepRepo.orgsWithKeepsLastThreeDays().map(_._1)
      val allOrgs = orgRepo.getByIds(orgIds.toSet)
      orgIds.map(id => allOrgs(id)).toSeq.filter(_.state == OrganizationStates.ACTIVE)
    }
    Future.sequence(orgs.map(org => orgStatsCommander.organizationStatisticsOverview(org))).map { orgStats =>
      Ok(html.admin.organizations(
        orgStats,
        "Three day active orgs",
        fakeOwnerId,
        (com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView _).andThen(asPlayHtml),
        1,
        orgs.size,
        pageSize
      ))
    }
  }

  private def isFake(orgId: Id[Organization]): Boolean = db.readOnlyMaster { implicit session =>
    orgExperimentRepo.hasExperiment(orgId, OrganizationExperimentType.FAKE)
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
    val orgStats = orgStatsCommander.organizationStatistics(orgId, adminId, numMemberRecos)
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

  def findOrganizationByName(orgName: String) = AdminUserPage.async { implicit request =>
    val orgs = db.readOnlyReplica { implicit session =>
      orgRepo.searchOrgsByNameFuzzy(orgName).sortBy(_.id.get)(Ordering[Id[Organization]].reverse).filter(_.state == OrganizationStates.ACTIVE)
    }
    if (orgs.isEmpty) {
      Future.successful(Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0)).flashing(
        "error" -> s"No results for '$orgName' found"
      ))
    } else {
      Future.sequence(orgs.map(org => orgStatsCommander.organizationStatisticsOverview(org))).map { orgs =>
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
      case Left(fail) => fail.asErrorResponse
      case Right(res) =>
        //next two line are to check that the impossible does not happen
        val updatedOrg = db.readOnlyMaster { implicit s => orgRepo.get(orgId) }
        assume(updatedOrg.ownerId == newOwnerId)
        /**
         * When we're creating orgs via the admin tool, we're setting a fake owner id as the owner to preserve data integrity.
         * Once we make a real user own the org there is no longer a need for that fake user and we're taking them out.
         */
        if (oldOwnerId == fakeOwnerId) {
          orgMembershipCommander.unsafeRemoveMembership(OrganizationMembershipRemoveRequest(orgId, requesterId = request.adminUserId.get, targetId = oldOwnerId), isAdmin = true)
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
    val targetId = Id[User](request.body.asFormUrlEncoded.get.apply("user-id").head.toLong)
    orgMembershipCandidateCommander.addCandidates(orgId, Set(targetId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def removeMember(orgId: Id[Organization]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val targetId = Id[User](request.body.get("user-id").flatMap(_.headOption).get.toLong)
    orgMembershipCommander.unsafeRemoveMembership(OrganizationMembershipRemoveRequest(orgId, requesterId = request.userId, targetId = targetId), isAdmin = true)
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def removeCandidate(orgId: Id[Organization]) = AdminUserPage(parse.tolerantFormUrlEncoded) { implicit request =>
    val userId = Id[User](request.body.get("user-id").flatMap(_.headOption).get.toLong)
    orgMembershipCandidateCommander.removeCandidates(orgId, Set(userId))
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def addMember(orgId: Id[Organization]) = AdminUserPage { implicit request =>
    val targetId = Id[User](request.body.asFormUrlEncoded.get.apply("user-id").head.toLong)
    db.readWrite { implicit s =>
      orgMembershipCommander.unsafeAddMembership(OrganizationMembershipAddRequest(orgId, requesterId = request.userId, targetId = targetId, OrganizationRole.MEMBER), isAdmin = true)
    }
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
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
      handleCommander.setOrganizationHandle(orgRepo.get(orgId), handle, overrideValidityCheck = true, overrideProtection = true)
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

  def addDomainOwnership(orgId: Id[Organization]) = AdminUserAction(parse.tolerantFormUrlEncoded) { implicit request =>
    val body = request.body
    val domainName = body.get("domainName").flatMap(_.headOption).get
    val orgView = com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId)
    NormalizedHostname.fromHostname(domainName) match {
      case Some(hostname) =>
        orgDomainOwnershipCommander.unsafeAddDomainOwnership(orgId, hostname.value)
        Redirect(orgView)
      case None => Redirect(orgView).flashing("error" -> "invalid domain format")
    }
  }

  def removeDomainOwnership(orgId: Id[Organization], domainHostname: String) = AdminUserAction { implicit request =>
    orgDomainOwnershipCommander.unsafeRemoveDomainOwnership(orgId, domainHostname)
    Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationViewBy(orgId))
  }

  def forceDeactivate(orgId: Id[Organization]) = AdminUserAction { implicit request =>
    implicit val context = HeimdalContext.empty
    val org = db.readOnlyReplica { implicit session => orgRepo.get(orgId) }
    val deleteResponse = orgCommander.deleteOrganization(OrganizationDeleteRequest(org.ownerId, org.id.get))
    deleteResponse match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => Redirect(com.keepit.controllers.admin.routes.AdminOrganizationController.organizationsView(0))
    }
  }

  def addFeatureToPlans() = AdminUserAction(parse.tolerantJson) { implicit request =>
    require((request.body \ "confirmation").as[String] == "really do it")
    val feature = (request.body \ "feature").as[Feature](Feature.reads)
    val setting = (request.body \ "setting").as[FeatureSetting](feature.settingReads)

    val newPlans = db.readWrite { implicit s =>
      paidPlanRepo.all.map { plan =>
        paidPlanRepo.save(plan.withNewEditableFeature(feature).withNewDefaultSetting(feature -> setting))
      }
    }
    Ok(Json.obj("changed" -> newPlans.map(_.id.get)))
  }
  def applyDefaultSettingsToOrgConfigs() = AdminUserAction(parse.tolerantJson) { implicit request =>
    require((request.body \ "confirmation").as[String] == "really do it")
    val deprecatedSettings = (request.body \ "deprecatedSettings").asOpt[OrganizationSettings](OrganizationSettings.dbFormat).getOrElse(OrganizationSettings.empty)

    val enum = Concurrent.unicast(onStart = { (channel: Concurrent.Channel[String]) =>
      FutureHelpers.iterate(Id[Organization](0))(fromId => db.readWriteAsync { implicit s =>
        val orgs = orgRepo.adminGetActiveFromId(fromId, limit = 50)
        val numChanged = orgs.count { org =>
          val account = paidAccountRepo.getByOrgId(org.id.get)
          val plan = paidPlanRepo.get(account.planId)
          val config = orgConfigRepo.getByOrgId(org.id.get)
          if (config.settings.features == plan.defaultSettings.features && deprecatedSettings.selections.isEmpty) false
          else {
            val newSettings = OrganizationSettings(plan.defaultSettings.selections.map {
              case (f, default) =>
                val updatedSetting =
                  if (deprecatedSettings.settingFor(f) == config.settings.settingFor(f)) default
                  else config.settings.settingFor(f).getOrElse(default)
                f -> updatedSetting
            })
            orgConfigRepo.save(config.withSettings(newSettings))
            true
          }
        }
        (orgs, numChanged)
      }.map {
        case (orgs, numChanged) =>
          channel.push(s"Changed $numChanged/${orgs.length} in the range ${orgs.map(_.id.get).minMaxOpt}\n")
          orgs.map(_.id.get).maxOpt
      }).andThen {
        case Success(finalOrgId) =>
          channel.push(s"Done! Final org processed was $finalOrgId\n")
        case Failure(fail) =>
          channel.push(s"Server error! ${fail.getMessage}\n")
      }.onComplete { _ => channel.eofAndEnd() }
    })

    Ok.chunked(enum)
  }

  def unsyncSlackLibraries(orgId: Id[Organization], doIt: Boolean = false) = AdminUserAction.async { implicit request =>
    val slackLibraryIds = db.readOnlyMaster { implicit session =>
      libRepo.getBySpaceAndKind(OrganizationSpace(orgId), LibraryKind.SLACK_CHANNEL).map(_.id.get)
    }
    if (doIt) {
      FutureHelpers.sequentialExec(slackLibraryIds)(libCommander.unsafeAsyncDeleteLibrary).map { _ =>
        val team = db.readWrite { implicit session =>
          slackTeamRepo.getByOrganizationId(orgId).map { slackTeam =>
            slackTeamRepo.save(slackTeam.withOrganizationId(None).withOrganizationId(Some(orgId)))
          }
        }
        Ok(s"Deleted ${slackLibraryIds.size} libraries for team $team")
      }
    } else {
      val team = db.readOnlyMaster { implicit session => slackTeamRepo.getByOrganizationId(orgId) }
      Future.successful(Ok(s"Should we doIt? We'll delete ${slackLibraryIds.size} libraries for team $team."))
    }
  }

  def sendBackfillScopesDMToOwnersWithoutKifiBot = AdminUserAction.async(parse.tolerantJson) { implicit request =>
    val members = (request.body \ "members").as[Set[Id[SlackTeamMembership]]] // if defined, only send to these org owners
    val slackUsersToSendTo = db.readOnlyMaster { implicit s =>
      slackMembershipRepo.getAllByIds(members)
      //if (members.isDefined) slackMembershipRepo.getAllByIds(members.get)
      //else getOwnersToSendTo()
    }
    sendBackfillScopesDM(slackUsersToSendTo).map(_ => Ok(Json.toJson(slackUsersToSendTo.map(_.slackTeamId))))
  }

  private def getOwnersToSendTo()(implicit session: RSession): Set[SlackTeamMembership] = {
    val teams = slackTeamRepo.getAllActiveWithOrgAndWithoutKifiBotToken()
    val orgs = orgRepo.getByIds(teams.flatMap(_.organizationId).toSet)
    val teamIdByOwnerId = teams.flatMap { t => t.organizationId.flatMap(orgs.get).map(_.ownerId -> t.slackTeamId) }.toMap
    slackMembershipRepo.getByUserIdsAndSlackTeams(teamIdByOwnerId).toSet
  }

  private def sendBackfillScopesDM(members: Set[SlackTeamMembership]): Future[Unit] = {
    import SlackAuthScope._
    import DescriptionElements._
    FutureHelpers.chunkySequentialExec(members, chunkSize = 100) { mem =>
      val authLink = com.keepit.controllers.core.routes.AuthController.startWithSlack(Some(mem.slackTeamId), extraScopes = Some(SlackAuthScope.stringifySet(pushAnywhereWithKifiBot))).url
      val text = DescriptionElements.formatForSlack(DescriptionElements(
        "Kifi team here - we made some major upgrades by creating a bot. To take advantage of them, you'll need to", "update your slack integration settings" --> LinkElement(authLink), "here.",
        "\n\n", "Learn about the new features including a bot & personal stats on your usage ", "here" --> LinkElement("https://www.kifi.com/") // todo(Cam): use the blog post link when it's ready
      ))
      val msg = SlackMessageRequest.fromKifi(text)
      slackClient.sendToSlackHoweverPossible(mem.slackTeamId, mem.slackUserId.asChannel, msg).map(s => mem.id.get -> true).recover { case _ => mem.id.get -> false }
    }
  }
}
