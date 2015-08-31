package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache.TransactionalCache
import com.keepit.common.controller.UserRequest
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationPermission.{ EDIT_ORGANIZATION, VIEW_ORGANIZATION }
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.payments.{ PlanManagementCommander, PaidPlan }

import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): OrganizationView
  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo
  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Organization], OrganizationInfo]
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
    basicOrganizationIdCache: BasicOrganizationIdCache) extends OrganizationCommander with Logging {

  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): OrganizationView = {
    db.readOnlyReplica { implicit session =>
      val organizationInfo = getOrganizationInfo(orgId, viewerIdOpt)
      val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
      OrganizationView(organizationInfo, membershipInfo)
    }
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
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).map(_.imagePath)

    BasicOrganization(
      orgId = Organization.publicId(orgId),
      ownerId = ownerId,
      handle = orgHandle,
      name = orgName,
      description = description,
      avatarPath = avatarPath)
  }

  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Organization], OrganizationInfo] = {
    orgIds.map(id => id -> getOrganizationInfo(id, viewerIdOpt)).toMap
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
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).map(_.imagePath)

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
      case OrganizationCreateRequest(_, initialValues) =>
        if (!areAllValidModifications(initialValues.asOrganizationModifications)) Some(OrganizationFail.BAD_PARAMETERS)
        else None

      case OrganizationModifyRequest(requesterId, orgId, modifications) =>
        val permissions = orgMembershipCommander.getPermissionsHelper(orgId, Some(requesterId))
        if (!permissions.contains(EDIT_ORGANIZATION)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else if (!areAllValidModifications(modifications)) Some(OrganizationFail.INVALID_MODIFICATIONS)
        else None

      case OrganizationDeleteRequest(requesterId, orgId) =>
        if (requesterId != orgRepo.get(orgId).ownerId) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None

      case OrganizationTransferRequest(requesterId, orgId, _) =>
        if (requesterId != orgRepo.get(orgId).ownerId) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None
    }
  }

  private def areAllValidModifications(modifications: OrganizationModifications): Boolean = {
    val badName = modifications.name.exists(_.isEmpty)
    val badBasePermissions = modifications.basePermissions.exists { bps =>
      def allRolesAreDescribed = bps.permissionsMap.keySet == OrganizationRole.allOpts
      def allRolesCanSeeOrg = OrganizationRole.all forall { role => bps.forRole(role).contains(VIEW_ORGANIZATION) }
      def adminsCanDoEverything = bps.forRole(OrganizationRole.ADMIN) == OrganizationPermission.all
      !allRolesAreDescribed || !allRolesCanSeeOrg || !adminsCanDoEverything
    }
    val normalizedSiteUrl = modifications.site.map { url =>
      if (url.startsWith("http://") || url.startsWith("https://")) url
      else "https://" + url
    }
    val badSiteUrl = normalizedSiteUrl.exists(URI.parse(_).isFailure)
    !badName && !badBasePermissions && !badSiteUrl
  }

  private def organizationWithModifications(org: Organization, modifications: OrganizationModifications): Organization = {
    org.withName(modifications.name.getOrElse(org.name))
      .withDescription(modifications.description.orElse(org.description))
      .withBasePermissions(modifications.basePermissions.getOrElse(org.basePermissions))
      .withSite(modifications.site.orElse(org.site))
  }

  def createOrganization(request: OrganizationCreateRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationCreateResponse] = {
    Try {
      db.readWrite { implicit session =>
        getValidationError(request) match {
          case Some(fail) =>
            Left(fail)
          case None =>
            val orgSkeleton = Organization(ownerId = request.requesterId, name = request.initialValues.name, primaryHandle = None, description = None, site = None)
            val orgTemplate = organizationWithModifications(orgSkeleton, request.initialValues.asOrganizationModifications)
            val org = handleCommander.autoSetOrganizationHandle(orgRepo.save(orgTemplate)) getOrElse {
              throw OrganizationFail.HANDLE_UNAVAILABLE
            }
            orgMembershipRepo.save(org.newMembership(userId = request.requesterId, role = OrganizationRole.ADMIN))
            planManagementCommander.createAndInitializePaidAccountForOrganization(org.id.get, PaidPlan.DEFAULT, request.requesterId, session) //this should get a .get when thing sare solidified
            organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
            Right(OrganizationCreateResponse(request, org))
        }
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
          if (request.modifications.basePermissions.nonEmpty) {
            val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
            applyNewBasePermissionsToMembers(memberships, org.basePermissions, modifiedOrg.basePermissions)
          }
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
          Right(OrganizationModifyResponse(request, orgRepo.save(modifiedOrg)))
        case Some(orgFail) => Left(orgFail)
      }
    }
  }
  private def applyNewBasePermissionsToMembers(memberships: Set[OrganizationMembership], oldBasePermissions: BasePermissions, newBasePermissions: BasePermissions)(implicit session: RWSession): Unit = {
    val membershipsByRole = memberships.groupBy(_.role)
    for ((role, memberships) <- membershipsByRole) {
      val beingAdded = newBasePermissions.forRole(role) -- oldBasePermissions.forRole(role)
      val beingRemoved = oldBasePermissions.forRole(role) -- newBasePermissions.forRole(role)
      memberships.foreach { membership =>
        // If the member is currently MISSING some permissions that normally come with their role
        // it means those permissions were explicitly revoked. We do not give them those back.
        val explicitlyRevoked = oldBasePermissions.forRole(role) -- membership.permissions

        val newPermissions = ((membership.permissions ++ beingAdded) -- beingRemoved) -- explicitlyRevoked
        orgMembershipRepo.save(membership.withPermissions(newPermissions))
      }
    }
  }

  def deleteOrganization(request: OrganizationDeleteRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationDeleteResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case None =>
          val org = orgRepo.get(request.orgId)

          val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
          memberships.foreach { membership => orgMembershipRepo.deactivate(membership) }

          val membershipCandidates = organizationMembershipCandidateRepo.getAllByOrgId(org.id.get)
          membershipCandidates.foreach { mc => organizationMembershipCandidateRepo.deactivate(mc) }

          val invites = orgInviteRepo.getAllByOrganization(org.id.get)
          invites.foreach(orgInviteRepo.deactivate)

          libraryRepo.getBySpace(org.id.get, excludeStates = Set.empty).foreach { lib =>
            libraryCommander.unsafeModifyLibrary(lib, LibraryModifyRequest(space = Some(lib.ownerId)))
          }

          orgRepo.save(org.sanitizeForDelete)
          handleCommander.reclaimAll(org.id.get, overrideProtection = true, overrideLock = true)
          planManagementCommander.deactivatePaidAccountForOrganziation(org.id.get, session)
          organizationAnalytics.trackOrganizationEvent(org, userRepo.get(request.requesterId), request)
          Right(OrganizationDeleteResponse(request))
        case Some(orgFail) => Left(orgFail)
      }
    }
  }

  def transferOrganization(request: OrganizationTransferRequest)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationTransferResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case Some(orgFail) => Left(orgFail)
        case None =>
          val org = orgRepo.get(request.orgId)
          orgMembershipRepo.getByOrgIdAndUserId(org.id.get, request.newOwner) match {
            case None => orgMembershipRepo.save(org.newMembership(request.newOwner, OrganizationRole.ADMIN))
            case Some(membership) => orgMembershipRepo.save(org.modifiedMembership(membership, newRole = OrganizationRole.ADMIN))
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
      if (modifications.basePermissions.nonEmpty) {
        val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
        applyNewBasePermissionsToMembers(memberships, org.basePermissions, modifiedOrg.basePermissions)
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
