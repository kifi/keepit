package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.controller.UserRequest
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ HttpClient, NonOKResponseException, DirectUrl, URI }
import com.keepit.common.performance.{ StatsdTiming, AlertingTimer }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImageSize }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationPermission.{ MANAGE_PLAN, EDIT_ORGANIZATION }
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.payments.{ PaidPlanRepo, PlanManagementCommander, PaidPlan, ActionAttribution }
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): OrganizationView
  def getOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], OrganizationView]
  def getOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationView
  def getBasicOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): BasicOrganizationView
  def getBasicOrganizationViewsHelper(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): Map[Id[Organization], BasicOrganizationView]
  def getBasicOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): BasicOrganizationView
  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo
  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationInfo]
  def getAccountFeatureSettings(orgId: Id[Organization]): OrganizationSettingsResponse
  def getExternalOrgConfiguration(orgId: Id[Organization]): ExternalOrganizationConfiguration
  def getExternalOrgConfigurationHelper(orgId: Id[Organization])(implicit session: RSession): ExternalOrganizationConfiguration
  def getBasicOrganizationHelper(orgId: Id[Organization])(implicit session: RSession): Option[BasicOrganization]
  def getBasicOrganizations(orgIds: Set[Id[Organization]]): Map[Id[Organization], BasicOrganization]
  def getOrganizationLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo]
  def createOrganization(request: OrganizationCreateRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationCreateResponse]
  def modifyOrganization(request: OrganizationModifyRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationModifyResponse]
  def setAccountFeatureSettings(request: OrganizationSettingsRequest): Either[OrganizationFail, OrganizationSettingsResponse]
  def deleteOrganization(request: OrganizationDeleteRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationDeleteResponse]
  def transferOrganization(request: OrganizationTransferRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationTransferResponse]

  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit
  def unsafeSetAccountFeatureSettings(orgId: Id[Organization], settings: OrganizationSettings)(implicit session: RWSession): OrganizationSettingsResponse
  def getOrgTrackingValues(orgId: Id[Organization]): OrgTrackingValues

}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    permissionCommander: PermissionCommander,
    orgRepo: OrganizationRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    paidPlanRepo: PaidPlanRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    organizationMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgInviteRepo: OrganizationInviteRepo,
    userExperimentRepo: UserExperimentRepo,
    organizationAvatarCommander: OrganizationAvatarCommander,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryCardCommander: LibraryCardCommander,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    orgExperimentRepo: OrganizationExperimentRepo,
    organizationAnalytics: OrganizationAnalytics,
    implicit val publicIdConfig: PublicIdConfiguration,
    handleCommander: HandleCommander,
    planManagementCommander: PlanManagementCommander,
    basicOrganizationIdCache: BasicOrganizationIdCache,
    httpClient: HttpClient,
    implicit val executionContext: ExecutionContext) extends OrganizationCommander with Logging {

  private val httpLock = new ReactiveLock(5)

  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): OrganizationView = {
    db.readOnlyReplica { implicit session => getOrganizationViewHelper(orgId, viewerIdOpt, authTokenOpt) }
  }

  def getOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], OrganizationView] = {
    db.readOnlyReplica { implicit session => orgIds.map(id => id -> getOrganizationViewHelper(id, viewerIdOpt, authTokenOpt)).toMap }
  }

  def getOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationView = {
    val organizationInfo = getOrganizationInfo(orgId, viewerIdOpt)
    val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
    OrganizationView(organizationInfo, membershipInfo)
  }

  def getBasicOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): BasicOrganizationView = {
    db.readOnlyReplica { implicit session => getBasicOrganizationViewHelper(orgId, viewerIdOpt, authTokenOpt) }
  }

  def getBasicOrganizationViewsHelper(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): Map[Id[Organization], BasicOrganizationView] = {
    orgIds.map(id => id -> getBasicOrganizationViewHelper(id, viewerIdOpt, authTokenOpt)).toMap
  }

  def getBasicOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): BasicOrganizationView = {
    // This function assumes that the org is active
    val basicOrganization = basicOrganizationIdCache.getOrElse(BasicOrganizationIdKey(orgId))(getBasicOrganizationHelper(orgId).get)
    val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
    BasicOrganizationView(basicOrganization, membershipInfo)
  }

  def getBasicOrganizations(orgIds: Set[Id[Organization]]): Map[Id[Organization], BasicOrganization] = {
    val cacheFormattedMap = db.readOnlyReplica { implicit session =>
      basicOrganizationIdCache.bulkGetOrElse(orgIds.map(BasicOrganizationIdKey)) { missing =>
        missing.map(_.id).map {
          orgId => orgId -> getBasicOrganizationHelper(orgId) // grab all the Option[BasicOrganization]
        }.collect {
          case (orgId, Some(basicOrg)) => orgId -> basicOrg // take only the active orgs (inactive ones are None)
        }.map {
          case (orgId, org) => (BasicOrganizationIdKey(orgId), org) // format them so the cache can understand them
        }.toMap
      }
    }
    cacheFormattedMap.map { case (orgKey, org) => (orgKey.id, org) }
  }

  def getBasicOrganizationHelper(orgId: Id[Organization])(implicit session: RSession): Option[BasicOrganization] = {
    val org = orgRepo.get(orgId)
    if (org.isInactive) None
    else {
      val orgHandle = org.handle
      val orgName = org.name
      val description = org.description

      val ownerId = userRepo.get(org.ownerId).externalId
      val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).imagePath

      Some(BasicOrganization(
        orgId = Organization.publicId(orgId),
        ownerId = ownerId,
        handle = orgHandle,
        name = orgName,
        description = description,
        avatarPath = avatarPath))
    }
  }

  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationInfo] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map(id => id -> getOrganizationInfo(id, viewerIdOpt)).toMap
    }
  }

  def getAccountFeatureSettings(orgId: Id[Organization]): OrganizationSettingsResponse = {
    db.readOnlyReplica { implicit session =>
      val config = orgConfigRepo.getByOrgId(orgId)
      OrganizationSettingsResponse(config)
    }
  }

  def getExternalOrgConfiguration(orgId: Id[Organization]): ExternalOrganizationConfiguration = {
    db.readOnlyReplica(implicit session => getExternalOrgConfigurationHelper(orgId))
  }

  def getExternalOrgConfigurationHelper(orgId: Id[Organization])(implicit session: RSession): ExternalOrganizationConfiguration = {
    val config = orgConfigRepo.getByOrgId(orgId)
    val plan = planManagementCommander.currentPlanHelper(orgId)
    val isPaid = !plan.displayName.toLowerCase.contains("free")
    ExternalOrganizationConfiguration(isPaid, OrganizationSettingsWithEditability(config.settings, plan.editableFeatures))
  }

  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo = {
    val viewerPermissions = permissionCommander.getOrganizationPermissions(orgId, viewerIdOpt)
    if (!viewerPermissions.contains(OrganizationPermission.VIEW_ORGANIZATION)) {
      airbrake.notify(s"Tried to serve up organization info for org $orgId to viewer $viewerIdOpt, but they do not have permission to view this org")
    }

    val org = orgRepo.get(orgId)
    if (org.state == OrganizationStates.INACTIVE) throw new Exception(s"inactive org: $org")
    val orgHandle = org.handle
    val orgName = org.name
    val description = org.description
    val site = org.site
    val ownerId = userRepo.get(org.ownerId).externalId

    val memberIds = {
      if (!viewerPermissions.contains(OrganizationPermission.VIEW_MEMBERS)) Seq.empty
      else orgMembershipRepo.getSortedMembershipsByOrgId(orgId, Offset(0), Limit(Int.MaxValue)).map(_.userId)
    }
    val members = userRepo.getAllUsers(memberIds).values.toSeq
    val membersAsBasicUsers = members.map(BasicUser.fromUser)
    val memberCount = members.length
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).imagePath
    val config = getExternalOrgConfigurationHelper(orgId)
    val numLibraries = countLibrariesVisibleToUserHelper(orgId, viewerIdOpt)

    OrganizationInfo(
      orgId = Organization.publicId(orgId),
      ownerId = ownerId,
      handle = orgHandle,
      name = orgName,
      description = description,
      site = site,
      avatarPath = avatarPath,
      members = membersAsBasicUsers,
      numMembers = memberCount,
      numLibraries = numLibraries,
      config = config)
  }

  private def getMembershipInfoHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationViewerInfo = {
    val membershipOpt = viewerIdOpt.flatMap { viewerId =>
      orgMembershipRepo.getByOrgIdAndUserId(orgId, viewerId)
    }
    val inviteOpt = orgInviteCommander.getViewerInviteInfo(orgId, viewerIdOpt, authTokenOpt)
    val permissions = permissionCommander.getOrganizationPermissions(orgId, viewerIdOpt)
    OrganizationViewerInfo(
      invite = inviteOpt,
      permissions = permissions,
      membership = membershipOpt.map(mem => OrganizationMembershipInfo(mem.role))
    )
  }

  def getOrganizationLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo] = {
    db.readOnlyReplica { implicit session =>
      val visibleLibraries = getLibrariesVisibleToUserHelper(orgId, userIdOpt, offset, limit)
      val basicOwnersByOwnerId = basicUserRepo.loadAll(visibleLibraries.map(_.ownerId).toSet)
      libraryCardCommander.createLibraryCardInfos(visibleLibraries, basicOwnersByOwnerId, userIdOpt, withFollowing = false, ProcessedImageSize.Medium.idealSize).seq
    }
  }

  private def getLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Library] = {
    val viewerLibraryMemberships = userIdOpt.map(libraryMembershipRepo.getWithUserId(_).map(_.libraryId).toSet).getOrElse(Set.empty[Id[Library]])
    val includeOrgVisibleLibs = userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(orgId, _).isDefined)
    libraryRepo.getVisibleOrganizationLibraries(orgId, includeOrgVisibleLibs, viewerLibraryMemberships, offset, limit)
  }
  private def countLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Int = {
    val viewerLibraryMemberships = userIdOpt.map(libraryMembershipRepo.getWithUserId(_).map(_.libraryId).toSet).getOrElse(Set.empty[Id[Library]])
    val includeOrgVisibleLibs = userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(orgId, _).isDefined)
    libraryRepo.countVisibleOrganizationLibraries(orgId, includeOrgVisibleLibs, viewerLibraryMemberships)
  }

  private def getValidationError(request: OrganizationRequest)(implicit session: RSession): Option[OrganizationFail] = {
    request match {
      case OrganizationCreateRequest(_, initialValues) => validateModifications(initialValues.asOrganizationModifications)

      case OrganizationModifyRequest(requesterId, orgId, modifications) =>
        val permissions = permissionCommander.getOrganizationPermissions(orgId, Some(requesterId))
        if (!permissions.contains(EDIT_ORGANIZATION)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else validateModifications(modifications)

      case OrganizationSettingsRequest(orgId, requesterId, settings) =>
        val permissions = permissionCommander.getOrganizationPermissions(orgId, Some(requesterId))
        if (!permissions.contains(MANAGE_PLAN)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else validateOrganizationSettings(orgId, settings)

      case OrganizationDeleteRequest(requesterId, orgId) =>
        if (requesterId != orgRepo.get(orgId).ownerId) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None

      case OrganizationTransferRequest(requesterId, orgId, _) =>
        if (requesterId != orgRepo.get(orgId).ownerId) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None
    }
  }

  private def validateModifications(modifications: OrganizationModifications): Option[OrganizationFail] = {
    val badName = modifications.name.exists(_.isEmpty)
    val normalizedSiteUrl = modifications.site.map { url =>
      if (url.startsWith("http://") || url.startsWith("https://")) url
      else "https://" + url
    }
    val badSiteUrl = normalizedSiteUrl.exists(URI.parse(_).isFailure)
    (badName, badSiteUrl) match {
      case (true, _) => Some(OrganizationFail.INVALID_MODIFY_NAME)
      case (_, true) => Some(OrganizationFail.INVALID_MODIFY_PERMISSIONS)
      case _ => None
    }
  }

  private def validateOrganizationSettings(orgId: Id[Organization], newSettings: OrganizationSettings)(implicit session: RSession): Option[OrganizationFail] = {
    def onlyModifyingEditableSettings = {
      val currentSettings = orgConfigRepo.getByOrgId(orgId).settings
      val editedFeatures = currentSettings diff newSettings

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
      .withSite(modifications.site.orElse(org.site))
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
          maybeNotifySlackOfNewOrganization(org.id.get, request.requesterId)

          val plan = paidPlanRepo.get(PaidPlan.DEFAULT)
          orgConfigRepo.save(OrganizationConfiguration(organizationId = org.id.get, settings = plan.defaultSettings))
          planManagementCommander.createAndInitializePaidAccountForOrganization(org.id.get, plan.id.get, request.requesterId, session).get

          orgMembershipRepo.save(org.newMembership(userId = request.requesterId, role = OrganizationRole.ADMIN))
          val orgGeneralLibrary = libraryCommander.unsafeCreateLibrary(LibraryInitialValues.forOrgGeneralLibrary(org), org.ownerId)
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)

          val orgView = getOrganizationViewHelper(org.id.get, Some(request.requesterId), None)
          Right(OrganizationCreateResponse(request, org, orgGeneralLibrary, orgView))
      }
    }
  }

  private def maybeNotifySlackOfNewOrganization(orgId: Id[Organization], userId: Id[User])(implicit session: RSession): Unit = {
    val isUserReal = !userExperimentRepo.hasExperiment(userId, UserExperimentType.FAKE)
    if (isUserReal) {
      val org = orgRepo.get(orgId)
      val user = userRepo.get(userId)

      val channel = "#org-members"
      val webhookUrl = "https://hooks.slack.com/services/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"

      val text = s"<http://www.kifi.com/${user.username.value}?kma=1|${user.fullName}> just created <http://admin.kifi.com/admin/organization/${org.id.get}|${org.name}>."
      val message = BasicSlackMessage(text = text, channel = Some(channel))

      val response = httpLock.withLockFuture(httpClient.postFuture(DirectUrl(webhookUrl), Json.toJson(message)))

      response.onComplete {
        case Failure(t: NonOKResponseException) => airbrake.notify(s"[notifySlackOfNewOrg] $t")
        case _ => airbrake.notify(s"[notifySlackOfNewOrg] Slack message request for $orgId created by $userId failed.")
      }
    }
  }

  def modifyOrganization(request: OrganizationModifyRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationModifyResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case None =>
          val org = orgRepo.get(request.orgId)

          val modifiedOrg = organizationWithModifications(org, request.modifications)
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
          val orgView = getOrganizationViewHelper(request.orgId, Some(request.requesterId), None)
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
          val response = unsafeSetAccountFeatureSettings(req.orgId, req.settings)
          Right(response)
      }
    }
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

          orgRepo.save(org.sanitizeForDelete)
          handleCommander.reclaimAll(org.id.get, overrideProtection = true, overrideLock = true)
          planManagementCommander.deactivatePaidAccountForOrganization(org.id.get, session)

          val requester = userRepo.get(request.requesterId)
          organizationAnalytics.trackOrganizationEvent(org, requester, request)

          val libsToDelete = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL).map(_.id.get)
          val libsToReturn = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.USER_CREATED, excludeState = None).map(_.id.get)
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
    val validationError = db.readOnlyReplica { implicit session => getValidationError(request) }
    validationError match {
      case Some(orgFail) => Left(orgFail)
      case None =>
        db.readWrite { implicit session =>
          val org = orgRepo.get(request.orgId)
          orgMembershipRepo.getByOrgIdAndUserId(org.id.get, request.newOwner, excludeState = None) match {
            case Some(membership) if membership.isActive =>
              orgMembershipRepo.save(org.modifiedMembership(membership, newRole = OrganizationRole.ADMIN))
            case inactiveMembershipOpt =>
              orgMembershipRepo.save(org.newMembership(request.newOwner, OrganizationRole.ADMIN).copy(id = inactiveMembershipOpt.flatMap(_.id)))
              planManagementCommander.registerNewAdmin(org.id.get, request.newOwner, ActionAttribution(user = Some(request.requesterId), admin = None))
              planManagementCommander.addUserAccountContact(org.id.get, request.newOwner, ActionAttribution(user = Some(request.requesterId), admin = None))
          }
          val modifiedOrg = orgRepo.save(org.withOwner(request.newOwner))

          libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(request.orgId), LibraryKind.SYSTEM_ORG_GENERAL).foreach { lib =>
            libraryCommander.unsafeTransferLibrary(lib.id.get, request.newOwner)
          }

          organizationAnalytics.trackOrganizationEvent(modifiedOrg, userRepo.get(request.requesterId), request)
          Right(OrganizationTransferResponse(request, modifiedOrg))
        }
    }
  }

  // For use in the Admin Organization controller. Don't use it elsewhere.
  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit = {
    if (!request.experiments.contains(UserExperimentType.ADMIN)) {
      throw new IllegalAccessException("unsafeModifyOrganization called from outside the admin page!")
    }
    db.readWrite { implicit session =>
      val org = orgRepo.get(orgId)
      val modifiedOrg = orgRepo.save(organizationWithModifications(org, modifications))
    }
  }

  def getOrgTrackingValues(orgId: Id[Organization]): OrgTrackingValues = {
    db.readOnlyReplica { implicit session =>
      val libraries = libraryRepo.getOrganizationLibraries(orgId)
      val libraryCount = libraries.length
      val keepCount = keepRepo.getByLibraryIds(libraries.map(_.id.get).toSet).count(keep => !KeepSource.imports.contains(keep.source))
      val inviteCount = orgInviteRepo.getCountByOrganization(orgId, decisions = Set(InvitationDecision.PENDING))
      val collabLibCount = libraryMembershipRepo.countWithAccessByLibraryId(libraries.map(_.id.get).toSet, LibraryAccess.READ_WRITE).count { case (_, memberCount) => memberCount > 0 }
      OrgTrackingValues(libraryCount, keepCount, inviteCount, collabLibCount)
    }
  }

  def unsafeSetAccountFeatureSettings(orgId: Id[Organization], settings: OrganizationSettings)(implicit session: RWSession): OrganizationSettingsResponse = {
    val currentConfig = orgConfigRepo.getByOrgId(orgId)
    val newConfig = orgConfigRepo.save(currentConfig.withSettings(settings))
    OrganizationSettingsResponse(newConfig)
  }
}
