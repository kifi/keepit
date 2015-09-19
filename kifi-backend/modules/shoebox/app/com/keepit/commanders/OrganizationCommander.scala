package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.controller.UserRequest
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.images.RawImageInfo
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.{ ImagePath, ImageSize }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationPermission.{ EDIT_ORGANIZATION, VIEW_ORGANIZATION }
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.payments.{ PlanManagementCommander, PaidPlan }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def getFullOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): FullOrganizationView
  def getFullOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], FullOrganizationView]
  def getBasicOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): BasicOrganizationView
  def getBasicOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], BasicOrganizationView]
  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo
  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationInfo]
  def getBasicOrganizations(orgIds: Set[Id[Organization]]): Map[Id[Organization], BasicOrganization]
  def getBasicOrganization(orgId: Id[Organization])(implicit session: RSession): BasicOrganization
  def getOrganizationLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo]
  def createOrganization(request: OrganizationCreateRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationCreateResponse]
  def modifyOrganization(request: OrganizationModifyRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationModifyResponse]
  def deleteOrganization(request: OrganizationDeleteRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationDeleteResponse]
  def transferOrganization(request: OrganizationTransferRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationTransferResponse]
  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit
  def getOrgTrackingValues(orgId: Id[Organization]): OrgTrackingValues
}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
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
    libraryInfoCommander: LibraryInfoCommander,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    orgExperimentRepo: OrganizationExperimentRepo,
    organizationAnalytics: OrganizationAnalytics,
    implicit val publicIdConfig: PublicIdConfiguration,
    handleCommander: HandleCommander,
    planManagementCommander: PlanManagementCommander,
    basicOrganizationIdCache: BasicOrganizationIdCache,
    implicit val executionContext: ExecutionContext) extends OrganizationCommander with Logging {

  def getFullOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): FullOrganizationView = {
    db.readOnlyReplica { implicit session => getFullOrganizationViewHelper(orgId, viewerIdOpt, authTokenOpt) }
  }

  def getFullOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], FullOrganizationView] = {
    db.readOnlyReplica { implicit session => orgIds.map(id => id -> getFullOrganizationViewHelper(id, viewerIdOpt, authTokenOpt)).toMap }
  }

  private def getFullOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): FullOrganizationView = {
    val organizationInfo = getOrganizationInfo(orgId, viewerIdOpt)
    val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
    FullOrganizationView(organizationInfo, membershipInfo)
  }

  def getBasicOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): BasicOrganizationView = {
    db.readOnlyReplica { implicit session => getBasicOrganizationViewHelper(orgId, viewerIdOpt, authTokenOpt) }
  }

  def getBasicOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], BasicOrganizationView] = {
    db.readOnlyReplica { implicit session => orgIds.map(id => id -> getBasicOrganizationViewHelper(id, viewerIdOpt, authTokenOpt)).toMap }
  }

  private def getBasicOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): BasicOrganizationView = {
    val basicOrganization = basicOrganizationIdCache.getOrElse(BasicOrganizationIdKey(orgId))(getBasicOrganization(orgId))
    val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
    BasicOrganizationView(basicOrganization, membershipInfo)
  }

  def getBasicOrganizations(orgIds: Set[Id[Organization]]): Map[Id[Organization], BasicOrganization] = {
    db.readOnlyReplica { implicit session =>
      basicOrganizationIdCache.bulkGetOrElse(orgIds.map(BasicOrganizationIdKey)) { missing =>
        missing.map(_.id).map { orgId => orgId -> getBasicOrganization(orgId) }.toMap.map {
          case (orgId, org) => (BasicOrganizationIdKey(orgId), org)
        }
      }
    }.map {
      case (orgKey, org) => (orgKey.id, org)
    }
  }

  def getBasicOrganization(orgId: Id[Organization])(implicit session: RSession): BasicOrganization = {
    val org = orgRepo.get(orgId)
    val orgHandle = org.handle
    val orgName = org.name
    val description = org.description

    val ownerId = userRepo.get(org.ownerId).externalId
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).imagePath

    BasicOrganization(
      orgId = Organization.publicId(orgId),
      ownerId = ownerId,
      handle = orgHandle,
      name = orgName,
      description = description,
      avatarPath = avatarPath)
  }

  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationInfo] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map(id => id -> getOrganizationInfo(id, viewerIdOpt)).toMap
    }
  }

  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo = {
    if (!orgMembershipCommander.getPermissionsHelper(orgId, viewerIdOpt).contains(OrganizationPermission.VIEW_ORGANIZATION)) {
      airbrake.notify(s"Tried to serve up an organization view for org $orgId to viewer $viewerIdOpt, but they do not have permission to view this org")
    }

    val org = orgRepo.get(orgId)
    if (org.state == OrganizationStates.INACTIVE) throw new Exception(s"inactive org: $org")
    val orgHandle = org.handle
    val orgName = org.name
    val description = org.description
    val site = org.site
    val ownerId = userRepo.get(org.ownerId).externalId

    val memberIds = orgMembershipRepo.getSortedMembershipsByOrgId(orgId, Offset(0), Limit(Int.MaxValue)).map(_.userId)
    val members = userRepo.getAllUsers(memberIds).values.toSeq
    val membersAsBasicUsers = members.map(BasicUser.fromUser)
    val memberCount = orgMembershipRepo.countByOrgId(orgId)
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).imagePath

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
      numLibraries = numLibraries)
  }

  private def getMembershipInfoHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationMembershipInfo = {
    val membershipOpt = viewerIdOpt.flatMap { viewerId =>
      orgMembershipRepo.getByOrgIdAndUserId(orgId, viewerId)
    }
    val inviteOpt = orgInviteCommander.getViewerInviteInfo(orgId, viewerIdOpt, authTokenOpt)
    OrganizationMembershipInfo(isInvited = inviteOpt.isDefined, invite = inviteOpt, role = membershipOpt.map(_.role), permissions = membershipOpt.map(_.permissions).getOrElse(orgRepo.get(orgId).basePermissions.forNonmember))
  }

  def getOrganizationLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo] = {
    db.readOnlyReplica { implicit session =>
      val visibleLibraries = getLibrariesVisibleToUserHelper(orgId, userIdOpt, offset, limit)
      val basicOwnersByOwnerId = basicUserRepo.loadAll(visibleLibraries.map(_.ownerId).toSet)
      val viewerOpt = userIdOpt.map(userRepo.get)
      libraryInfoCommander.createLibraryCardInfos(visibleLibraries, basicOwnersByOwnerId, viewerOpt, withFollowing = false, ProcessedImageSize.Medium.idealSize).seq
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
        val permissions = orgMembershipCommander.getPermissionsHelper(orgId, Some(requesterId))
        if (!permissions.contains(EDIT_ORGANIZATION)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else validateModifications(modifications)

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
    val badPermissionsChange = modifications.permissionsDiff.exists { pdiff =>
      def roleCannotSeeOrg = OrganizationRole.all.exists { role => pdiff.removed(Some(role)).contains(VIEW_ORGANIZATION) }
      def messedWithAdmins = pdiff.added(Some(OrganizationRole.ADMIN)).nonEmpty || pdiff.removed(Some(OrganizationRole.ADMIN)).nonEmpty // not going to tackle this right now, but we may need to take this check off with new permissions e.g. FORCE_EDIT_LIBRARIES
      roleCannotSeeOrg || messedWithAdmins
    }
    val normalizedSiteUrl = modifications.site.map { url =>
      if (url.startsWith("http://") || url.startsWith("https://")) url
      else "https://" + url
    }
    val badSiteUrl = normalizedSiteUrl.exists(URI.parse(_).isFailure)
    (badName, badPermissionsChange, badSiteUrl) match {
      case (true, _, _) => Some(OrganizationFail.INVALID_MODIFY_NAME)
      case (_, true, _) => Some(OrganizationFail.INVALID_MODIFY_PERMISSIONS)
      case (_, _, true) => Some(OrganizationFail.INVALID_MODIFY_SITEURL)
      case _ => None
    }
  }

  private def organizationWithModifications(org: Organization, modifications: OrganizationModifications): Organization = {
    org.withName(modifications.name.getOrElse(org.name))
      .withDescription(modifications.description.orElse(org.description))
      .applyPermissionsDiff(modifications.permissionsDiff.getOrElse(PermissionsDiff.empty))
      .withSite(modifications.site.orElse(org.site))
  }

  def createOrganization(request: OrganizationCreateRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationCreateResponse] = {
    Try {
      db.readOnlyMaster { implicit session => getValidationError(request) } match {
        case Some(fail) =>
          Left(fail)
        case None =>
          val org = db.readWrite { implicit session =>
            val orgSkeleton = Organization(ownerId = request.requesterId, name = request.initialValues.name, primaryHandle = None, description = None, site = None)
            val orgTemplate = organizationWithModifications(orgSkeleton, request.initialValues.asOrganizationModifications)
            val org = handleCommander.autoSetOrganizationHandle(orgRepo.save(orgTemplate)) getOrElse (throw OrganizationFail.HANDLE_UNAVAILABLE)
            orgMembershipRepo.save(org.newMembership(userId = request.requesterId, role = OrganizationRole.ADMIN))
            planManagementCommander.createAndInitializePaidAccountForOrganization(org.id.get, PaidPlan.DEFAULT, request.requesterId, session) //this should get a .get when things are solidified
            organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
            org
          }
          Right(OrganizationCreateResponse(request, org))
      }
    } match {
      case Success(Left(fail)) => Left(fail)
      case Success(Right(response)) => Right(response)
      case Failure(OrganizationFail.HANDLE_UNAVAILABLE) => Left(OrganizationFail.HANDLE_UNAVAILABLE)
      case Failure(ex) => throw ex
    }
  }

  def modifyOrganization(request: OrganizationModifyRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationModifyResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case None =>
          val org = orgRepo.get(request.orgId)

          val modifiedOrg = organizationWithModifications(org, request.modifications)
          if (request.modifications.permissionsDiff.isDefined) {
            planManagementCommander.applyNewBasePermissionsToMembers(org.id.get, org.basePermissions, modifiedOrg.basePermissions)
          }
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
          Right(OrganizationModifyResponse(request, orgRepo.save(modifiedOrg)))
        case Some(orgFail) => Left(orgFail)
      }
    }
  }

  def deleteOrganization(request: OrganizationDeleteRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationDeleteResponse] = {
    val validationError = db.readOnlyReplica { implicit session => getValidationError(request) }
    validationError match {
      case Some(orgFail) => Left(orgFail)
      case None =>
        val libsToReturn = db.readWrite { implicit session =>
          val org = orgRepo.get(request.orgId)

          val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
          memberships.foreach { membership => orgMembershipRepo.deactivate(membership) }

          val membershipCandidates = organizationMembershipCandidateRepo.getAllByOrgId(org.id.get)
          membershipCandidates.foreach { mc => organizationMembershipCandidateRepo.deactivate(mc) }

          val invites = orgInviteRepo.getAllByOrganization(org.id.get)
          invites.foreach(orgInviteRepo.deactivate)

          orgRepo.save(org.sanitizeForDelete)
          handleCommander.reclaimAll(org.id.get, overrideProtection = true, overrideLock = true)
          planManagementCommander.deactivatePaidAccountForOrganziation(org.id.get, session)

          val requester = userRepo.get(request.requesterId)
          organizationAnalytics.trackOrganizationEvent(org, requester, request)

          val libsToReturn = libraryRepo.getBySpace(org.id.get, excludeState = None)
          libsToReturn
        }

        // Modifying an org opens its own DB session. Do these separately from the rest of the logic
        val returningLibsFut = Future {
          libsToReturn.foreach { lib =>
            libraryCommander.unsafeModifyLibrary(lib, LibraryModifyRequest(space = Some(lib.ownerId)))
          }
        }
        Right(OrganizationDeleteResponse(request, returningLibsFut))
    }
  }

  def transferOrganization(request: OrganizationTransferRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationTransferResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case Some(orgFail) => Left(orgFail)
        case None =>
          val org = orgRepo.get(request.orgId)
          orgMembershipRepo.getByOrgIdAndUserId(org.id.get, request.newOwner, excludeState = None) match {
            case Some(membership) if membership.isActive =>
              orgMembershipRepo.save(org.modifiedMembership(membership, newRole = OrganizationRole.ADMIN))
            case inactiveMembershipOpt =>
              orgMembershipRepo.save(org.newMembership(request.newOwner, OrganizationRole.ADMIN).copy(id = inactiveMembershipOpt.flatMap(_.id)))
          }
          val modifiedOrg = orgRepo.save(org.withOwner(request.newOwner))
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
      if (modifications.permissionsDiff.isDefined) {
        planManagementCommander.applyNewBasePermissionsToMembers(org.id.get, org.basePermissions, modifiedOrg.basePermissions)
      }
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
}
