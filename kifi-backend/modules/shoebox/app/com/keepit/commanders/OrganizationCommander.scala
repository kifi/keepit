package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.controller.UserRequest
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, SlackLog }
import com.keepit.common.performance.{ AlertingTimer, StatsdTiming }
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationPermission.EDIT_ORGANIZATION
import com.keepit.model._
import com.keepit.payments.{ ActionAttribution, CreditRewardCommander, PaidPlan, PaidPlanRepo, PlanManagementCommander, RewardTrigger }
import com.keepit.slack.models.{ LibraryToSlackChannelRepo, SlackChannelToLibraryRepo, SlackTeamRepo }
import com.keepit.slack.{ InhouseSlackChannel, InhouseSlackClient }
import play.api.Play

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def createOrganization(request: OrganizationCreateRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationCreateResponse]
  def modifyOrganization(request: OrganizationModifyRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationModifyResponse]
  def transferOrganization(request: OrganizationTransferRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationTransferResponse]
  def unsafeTransferOrganization(request: OrganizationTransferRequest, isAdmin: Boolean = false)(implicit session: RWSession, eventContext: HeimdalContext): OrganizationTransferResponse
  def setAccountFeatureSettings(req: OrganizationSettingsRequest): Either[OrganizationFail, OrganizationSettingsResponse]
  def unsafeSetAccountFeatureSettings(orgId: Id[Organization], settings: OrganizationSettings, requesterIdOpt: Option[Id[User]])(implicit session: RWSession): OrganizationSettingsResponse
  def deleteOrganization(request: OrganizationDeleteRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationDeleteResponse]
  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit
}

class OrganizationCommanderImpl @Inject() (
  db: Database,
  orgRepo: OrganizationRepo,
  organizationMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
  orgMembershipRepo: OrganizationMembershipRepo,
  orgInviteRepo: OrganizationInviteRepo,
  orgConfigRepo: OrganizationConfigurationRepo,
  userRepo: UserRepo,
  paidPlanRepo: PaidPlanRepo,
  libraryRepo: LibraryRepo,
  orgDomainOwnershipRepo: OrganizationDomainOwnershipRepo,
  organizationInfoCommander: OrganizationInfoCommander,
  planManagementCommander: PlanManagementCommander,
  permissionCommander: PermissionCommander,
  handleCommander: HandleCommander,
  libraryCommander: LibraryCommander,
  orgMembershipCommander: OrganizationMembershipCommander,
  creditRewardCommander: CreditRewardCommander,
  organizationAnalytics: OrganizationAnalytics,
  slackTeamRepo: SlackTeamRepo,
  slackChannelToLibraryRepo: SlackChannelToLibraryRepo,
  libraryToSlackChannelRepo: LibraryToSlackChannelRepo,
  eliza: ElizaServiceClient,
  airbrake: AirbrakeNotifier,
  clock: Clock,
  implicit val executionContext: ExecutionContext,
  implicit val inhouseSlackClient: InhouseSlackClient)
    extends OrganizationCommander with Logging {
  private val slackLog = new SlackLog(InhouseSlackChannel.ENG_SHOEBOX)

  private def getValidationError(request: OrganizationRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val error = request match {
      case OrganizationCreateRequest(_, initialValues) => validateModifications(initialValues.asOrganizationModifications)

      case OrganizationModifyRequest(requesterId, orgId, modifications) =>
        val permissions = permissionCommander.getOrganizationPermissions(orgId, Some(requesterId))
        if (!permissions.contains(EDIT_ORGANIZATION)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else validateModifications(modifications)

      case OrganizationSettingsRequest(orgId, requesterId, settings) =>
        val permissions = permissionCommander.getOrganizationPermissions(orgId, Some(requesterId))
        val canEditSettings = settings.features.forall(feature => permissions.contains(feature.editableWith))
        if (!canEditSettings) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else validateOrganizationSettings(orgId, settings)

      case OrganizationDeleteRequest(requesterId, orgId) =>
        if (requesterId != orgRepo.get(orgId).ownerId) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None

      case OrganizationTransferRequest(requesterId, orgId, _) =>
        if (requesterId != orgRepo.get(orgId).ownerId) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None
      case _ => None
    }

    error tap {
      case Some(err) => slackLog.warn("Validation error!", err.message, "for request", request.toString)
      case _ =>
    }
  }

  private def validateModifications(modifications: OrganizationModifications): Option[OrganizationFail] = {
    val badName = modifications.name.exists(_.isEmpty)
    val badSiteUrl = modifications.site.exists(!_.isValid)

    Stream(
      badName -> OrganizationFail.INVALID_MODIFY_NAME,
      badSiteUrl -> OrganizationFail.INVALID_MODIFY_SITEURL
    ).collect { case (true, fail) => fail }.headOption
  }

  private def validateOrganizationSettings(orgId: Id[Organization], newSettings: OrganizationSettings)(implicit session: RSession): Option[OrganizationFail] = {
    def onlyModifyingEditableSettings = {
      val currentSettings = orgConfigRepo.getByOrgId(orgId).settings
      val editedFeatures = currentSettings editedFeatures newSettings

      val plan = planManagementCommander.currentPlanHelper(orgId)
      val editableFeatures = paidPlanRepo.get(plan.id.get).editableFeatures
      editedFeatures subsetOf editableFeatures
    }
    if (!onlyModifyingEditableSettings) Some(OrganizationFail.MODIFYING_UNEDITABLE_SETTINGS)
    else None
  }

  private def organizationWithModifications(org: Organization, modifications: OrganizationModifications): Organization = {
    org.withName(modifications.name.getOrElse(org.name))
      .withDescription(modifications.description.orElse(org.description))
      .withSite(modifications.site.map(_.value).getOrElse(org.site))
  }

  @AlertingTimer(2 seconds)
  @StatsdTiming("OrganizationCommander.createOrganization")
  def createOrganization(request: OrganizationCreateRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationCreateResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case Some(fail) => Left(fail)
        case None =>
          val orgSkeleton = Organization(ownerId = request.requesterId, name = request.initialValues.name, primaryHandle = None, description = None, site = None)
          val orgTemplate = organizationWithModifications(orgSkeleton, request.initialValues.asOrganizationModifications)
          val savedOrg = orgRepo.save(orgTemplate)
          val org = handleCommander.autoSetOrganizationHandle(savedOrg) getOrElse (throw OrganizationFail.HANDLE_UNAVAILABLE)

          val plan = if (Play.maybeApplication.exists(Play.isProd(_))) paidPlanRepo.get(PaidPlan.DEFAULT) else paidPlanRepo.get(Id[PaidPlan](1L))
          orgConfigRepo.save(OrganizationConfiguration(organizationId = org.id.get, settings = plan.defaultSettings))
          planManagementCommander.createAndInitializePaidAccountForOrganization(org.id.get, plan.id.get, request.requesterId, session).get

          orgMembershipRepo.save(OrganizationMembership(organizationId = org.id.get, userId = request.requesterId, role = OrganizationRole.ADMIN))
          val orgGeneralLibrary = libraryCommander.unsafeCreateLibrary(LibraryInitialValues.forOrgGeneralLibrary(org), org.ownerId)
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)

          session.onTransactionSuccess {
            eliza.flush(request.requesterId)
          }

          val orgView = organizationInfoCommander.getOrganizationViewHelper(org.id.get, Some(request.requesterId), None)
          Right(OrganizationCreateResponse(request, org, orgGeneralLibrary, orgView))
      }
    }
  }

  def modifyOrganization(request: OrganizationModifyRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationModifyResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case None =>
          val org = orgRepo.get(request.orgId)
          val modifiedOrg = organizationWithModifications(org, request.modifications)
          request.modifications.description.filter(_.nonEmpty).foreach { d =>
            creditRewardCommander.registerRewardTrigger(RewardTrigger.OrganizationDescriptionAdded(org.id.get, modifiedOrg))
          }
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
          val orgView = organizationInfoCommander.getOrganizationViewHelper(request.orgId, Some(request.requesterId), None)
          Right(OrganizationModifyResponse(request, orgRepo.save(modifiedOrg), orgView))
        case Some(orgFail) => Left(orgFail)
      }
    }
  }

  def setAccountFeatureSettings(req: OrganizationSettingsRequest): Either[OrganizationFail, OrganizationSettingsResponse] = {
    db.readWrite { implicit session =>
      getValidationError(req) match {
        case Some(fail) => Left(fail)
        case None =>
          val response = unsafeSetAccountFeatureSettings(req.orgId, req.settings, Some(req.requesterId))
          Right(response)
      }
    }
  }

  def unsafeSetAccountFeatureSettings(orgId: Id[Organization], settings: OrganizationSettings, requesterIdOpt: Option[Id[User]])(implicit session: RWSession): OrganizationSettingsResponse = {
    val currentConfig = orgConfigRepo.getByOrgId(orgId)
    val augmentedSettings = augmentSettings(requesterIdOpt, settings, currentConfig.settings)
    val newConfig = orgConfigRepo.save(currentConfig.updateSettings(augmentedSettings))

    val members = orgMembershipRepo.getAllByOrgId(orgId)
    if (currentConfig.settings != settings) {
      session.onTransactionSuccess {
        members.foreach(mem => eliza.flush(mem.userId))
      }
    }
    OrganizationSettingsResponse(newConfig)
  }

  private def augmentSettings(requesterIdOpt: Option[Id[User]], newSettings: OrganizationSettings, existingSettings: OrganizationSettings)(implicit session: RWSession) = {
    // If we ever do transformations on settings before persisting
    def augmentBlacklist(blacklist: ClassFeature.Blacklist) = {
      import ClassFeature.{ Blacklist, BlacklistEntry, SlackIngestionDomainBlacklist }
      val existingList = existingSettings.settingFor(SlackIngestionDomainBlacklist).collect { case blk: Blacklist => blk.entries }.getOrElse(Seq.empty)
      val userOpt = requesterIdOpt.map(userRepo.get)
      Blacklist(blacklist.entries.map { entry =>
        if (!existingList.exists(_.path == entry.path)) { // New record
          BlacklistEntry(userOpt.map(_.externalId), Some(clock.now), entry.path)
        } else entry
      })
    }

    OrganizationSettings(newSettings.selections.map {
      case (ClassFeature.SlackIngestionDomainBlacklist, blacklist: ClassFeature.Blacklist) =>
        ClassFeature.SlackIngestionDomainBlacklist -> augmentBlacklist(blacklist)
      case setting => setting
    })
  }

  def deleteOrganization(request: OrganizationDeleteRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationDeleteResponse] = {
    val validationError = db.readOnlyReplica { implicit session => getValidationError(request) }
    validationError match {
      case Some(orgFail) => Left(orgFail)
      case None =>
        val (libsToReturn, libsToDelete) = db.readWrite { implicit session =>
          val org = orgRepo.get(request.orgId)

          val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
          memberships.foreach { membership => orgMembershipRepo.deactivate(membership) }

          val membershipCandidates = organizationMembershipCandidateRepo.getAllByOrgId(org.id.get)
          membershipCandidates.foreach { mc => organizationMembershipCandidateRepo.deactivate(mc) }

          val invites = orgInviteRepo.getAllByOrgId(org.id.get)
          invites.foreach(orgInviteRepo.deactivate)

          val domains = orgDomainOwnershipRepo.getOwnershipsForOrganization(org.id.get)
          domains.foreach(orgDomainOwnershipRepo.deactivate)

          orgConfigRepo.deactivate(orgConfigRepo.getByOrgId(org.id.get))

          slackTeamRepo.getByOrganizationId(org.id.get).foreach { slackTeam =>
            slackTeamRepo.save(slackTeam.withOrganizationId(None))
            slackChannelToLibraryRepo.getBySlackTeam(slackTeam.slackTeamId).foreach(slackChannelToLibraryRepo.deactivate(_))
            libraryToSlackChannelRepo.getBySlackTeam(slackTeam.slackTeamId).foreach(libraryToSlackChannelRepo.deactivate(_))
          }

          orgRepo.save(org.sanitizeForDelete)
          handleCommander.reclaimAll(org.id.get, overrideProtection = true, overrideLock = true)
          planManagementCommander.deactivatePaidAccountForOrganization(org.id.get, session)

          val requester = userRepo.get(request.requesterId)
          organizationAnalytics.trackOrganizationEvent(org, requester, request)

          val libsToDelete = libraryRepo.getBySpaceAndKinds(org.id.get, Set(LibraryKind.SYSTEM_ORG_GENERAL, LibraryKind.SLACK_CHANNEL)).map(_.id.get)
          val libsToReturn = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.USER_CREATED).map(_.id.get)

          (libsToReturn, libsToDelete)
        }

        val deletingLibsFut = Future.sequence(libsToDelete.map(libraryCommander.unsafeAsyncDeleteLibrary)).map { _ => }

        // Modifying an org opens its own DB session. Do these separately from the rest of the logic
        val returningLibsFut = Future {
          libsToReturn.foreach { libId =>
            val lib = db.readOnlyReplica { implicit session => libraryRepo.get(libId) }
            libraryCommander.unsafeModifyLibrary(lib, LibraryModifications(space = Some(lib.ownerId)))
          }
        }
        Right(OrganizationDeleteResponse(request, returningLibsFut, deletingLibsFut))
    }
  }

  def transferOrganization(request: OrganizationTransferRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationTransferResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case Some(orgFail) => Left(orgFail)
        case None => Right(unsafeTransferOrganization(request))
      }
    }
  }

  def unsafeTransferOrganization(request: OrganizationTransferRequest, isAdmin: Boolean = false)(implicit session: RWSession, eventContext: HeimdalContext): OrganizationTransferResponse = {
    val org = orgRepo.get(request.orgId)
    if (orgMembershipRepo.getByOrgIdAndUserId(org.id.get, request.newOwner).isDefined) {
      orgMembershipCommander.unsafeModifyMembership(OrganizationMembershipModifyRequest(request.orgId, request.requesterId, request.newOwner, OrganizationRole.ADMIN), isAdmin = isAdmin)
    } else {
      orgMembershipCommander.unsafeAddMembership(OrganizationMembershipAddRequest(request.orgId, request.requesterId, request.newOwner, OrganizationRole.ADMIN), isAdmin = isAdmin)
    }
    planManagementCommander.addUserAccountContactHelper(org.id.get, request.newOwner, ActionAttribution(request.requesterId, isAdmin))
    val modifiedOrg = orgRepo.save(org.withOwner(request.newOwner))
    libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(request.orgId), LibraryKind.SYSTEM_ORG_GENERAL).foreach { lib =>
      libraryCommander.unsafeTransferLibrary(lib.id.get, request.newOwner)
    }

    organizationAnalytics.trackOrganizationEvent(modifiedOrg, userRepo.get(request.requesterId), request)
    OrganizationTransferResponse(request, modifiedOrg)
  }

  // For use in the Admin Organization controller. Don't use it elsewhere.
  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit = {
    if (!request.experiments.contains(UserExperimentType.ADMIN)) {
      throw new IllegalAccessException("unsafeModifyOrganization called from outside the admin page!")
    }
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      orgRepo.save(organizationWithModifications(org, modifications))
    }
  }
}
