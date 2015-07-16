package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.controller.UserRequest
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.ImageSize
import com.keepit.model.OrganizationPermission.{ EDIT_ORGANIZATION, VIEW_ORGANIZATION }
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def getAllOrganizationIds: Seq[Id[Organization]]
  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]]): OrganizationView
  def getOrganizationCards(orgIds: Seq[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationCard]
  def getLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo]
  def isValidRequest(request: OrganizationRequest)(implicit session: RSession): Boolean
  def createOrganization(request: OrganizationCreateRequest): Either[OrganizationFail, OrganizationCreateResponse]
  def modifyOrganization(request: OrganizationModifyRequest): Either[OrganizationFail, OrganizationModifyResponse]
  def deleteOrganization(request: OrganizationDeleteRequest): Either[OrganizationFail, OrganizationDeleteResponse]
  def transferOrganization(request: OrganizationTransferRequest): Either[OrganizationFail, OrganizationTransferResponse]

  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit
}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteRepo: OrganizationInviteRepo,
    organizationAvatarCommander: OrganizationAvatarCommander,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration,
    handleCommander: HandleCommander) extends OrganizationCommander with Logging {

  // TODO(ryan): do the smart thing and add a limit/offset
  def getAllOrganizationIds: Seq[Id[Organization]] = db.readOnlyReplica { implicit session => orgRepo.all().map(_.id.get) }

  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]]): OrganizationView = {
    db.readOnlyReplica { implicit session => getOrganizationViewHelper(orgId, viewerIdOpt) }
  }
  def getOrganizationCards(orgIds: Seq[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationCard] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map { orgId => orgId -> getOrganizationCardHelper(orgId, viewerIdOpt) }.toMap
    }
  }
  private def getOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationView = {
    if (!orgMembershipCommander.getPermissionsHelper(orgId, viewerIdOpt).contains(OrganizationPermission.VIEW_ORGANIZATION)) {
      airbrake.notify(s"Tried to serve up an organization view for org $orgId to viewer $viewerIdOpt, but they do not have permission to view this org")
    }

    val org = orgRepo.get(orgId)
    val orgHandle = org.getHandle
    val orgName = org.name
    val description = org.description

    val ownerId = userRepo.get(org.ownerId).externalId

    val memberIds = orgMembershipRepo.getByOrgId(orgId, Limit(8), Offset(0)).map(_.userId)
    val members = userRepo.getAllUsers(memberIds).values.toSeq
    val membersAsBasicUsers = members.map(BasicUser.fromUser)
    val memberCount = orgMembershipRepo.countByOrgId(orgId)
    val avatarPath = organizationAvatarCommander.getBestImage(orgId, ImageSize(200, 200)).map(_.imagePath)

    // TODO(ryan): this is so bad, surely there is a better way
    val numLibraries = getLibrariesVisibleToUserHelper(orgId, viewerIdOpt, offset = Offset(0), limit = Limit(Int.MaxValue)).length

    OrganizationView(
      orgId = Organization.publicId(orgId),
      ownerId = ownerId,
      handle = orgHandle,
      name = orgName,
      description = description,
      avatarPath = avatarPath,
      members = membersAsBasicUsers,
      numMembers = memberCount,
      numLibraries = numLibraries)
  }

  private def getOrganizationCardHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationCard = {
    if (!orgMembershipCommander.getPermissionsHelper(orgId, viewerIdOpt).contains(OrganizationPermission.VIEW_ORGANIZATION)) {
      airbrake.notify(s"Tried to serve up an organization card for org $orgId to viewer $viewerIdOpt, but they do not have permission to view this org")
    }
    val org = orgRepo.get(orgId)
    val orgHandle = org.getHandle
    val orgName = org.name
    val description = org.description

    val ownerId = userRepo.get(org.ownerId).externalId

    val numMembers = orgMembershipRepo.countByOrgId(orgId)
    val avatarPath = organizationAvatarCommander.getBestImage(orgId, ImageSize(200, 200)).map(_.imagePath)

    // TODO(ryan): this is bad, surely there is a better way
    val numLibraries = getLibrariesVisibleToUserHelper(orgId, viewerIdOpt, offset = Offset(0), limit = Limit(Int.MaxValue)).length

    OrganizationCard(
      orgId = Organization.publicId(orgId),
      ownerId = ownerId,
      handle = orgHandle,
      name = orgName,
      description = description,
      avatarPath = avatarPath,
      numMembers = numMembers,
      numLibraries = numLibraries)
  }

  def getLibrariesVisibleToUser(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit): Seq[LibraryCardInfo] = {
    val visibleLibraries = getLibrariesVisibleToUserHelper(orgId, userIdOpt, offset, limit)

    val (basicOwnersByOwnerId, viewerOpt) = db.readOnlyReplica { implicit session =>
      val basicOwnersByOwnerId = visibleLibraries.map(lib => lib.ownerId -> basicUserRepo.load(lib.ownerId)).toMap
      val viewerOpt = userIdOpt.map { userId =>
        userRepo.get(userId)
      }
      (basicOwnersByOwnerId, viewerOpt)
    }
    db.readOnlyReplica { implicit session =>
      libraryCommander.createLibraryCardInfos(visibleLibraries, basicOwnersByOwnerId, viewerOpt, withFollowing = false, ProcessedImageSize.Medium.idealSize).seq
    }
  }

  private def getLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]], offset: Offset, limit: Limit)(implicit session: RSession): Seq[Library] = {
    val allLibraries = libraryRepo.getBySpace(LibrarySpace.fromOrganizationId(orgId)).toSeq
    val visibleLibraries = allLibraries.filter(lib => libraryCommander.canViewLibraryHelper(userIdOpt, lib))
    visibleLibraries.drop(offset.value.toInt).take(limit.value.toInt)
  }

  def isValidRequest(request: OrganizationRequest)(implicit session: RSession): Boolean = {
    getValidationError(request).isEmpty
  }

  def getValidationError(request: OrganizationRequest)(implicit session: RSession): Option[OrganizationFail] = {
    request match {
      case OrganizationCreateRequest(createrId, initialValues) =>
        if (!areAllValidModifications(initialValues.asOrganizationModifications)) {
          Some(OrganizationFail.BAD_PARAMETERS)
        } else None
      case OrganizationModifyRequest(requesterId, orgId, modifications) =>
        val permissions = orgMembershipRepo.getByOrgIdAndUserId(orgId, requesterId).map(_.permissions)
        if (!permissions.exists(_.contains(EDIT_ORGANIZATION))) {
          Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        } else if (!areAllValidModifications(modifications)) {
          Some(OrganizationFail.BAD_PARAMETERS)
        } else {
          None
        }
      case OrganizationDeleteRequest(requesterId, orgId) =>
        if (requesterId != orgRepo.get(orgId).ownerId) {
          Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        } else None
      case OrganizationTransferRequest(requesterId, orgId, _) =>
        if (requesterId != orgRepo.get(orgId).ownerId) {
          Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        } else None
    }
  }

  private def areAllValidModifications(modifications: OrganizationModifications): Boolean = {
    val badName = modifications.name.exists(_.isEmpty)
    val badBasePermissions = modifications.basePermissions.exists { bps =>
      // Are there any members that can't even see the organization?
      OrganizationRole.all exists { role => !(bps.permissionsMap.contains(Some(role)) && bps.forRole(role).contains(VIEW_ORGANIZATION)) }
    }
    !badName && !badBasePermissions
  }

  private def organizationWithModifications(org: Organization, modifications: OrganizationModifications): Organization = {
    org.withName(modifications.name.getOrElse(org.name))
      .withDescription(modifications.description.orElse(org.description))
      .withBasePermissions(modifications.basePermissions.getOrElse(org.basePermissions))
  }

  def createOrganization(request: OrganizationCreateRequest): Either[OrganizationFail, OrganizationCreateResponse] = {
    Try {
      db.readWrite { implicit session =>
        if (!isValidRequest(request)) None
        else {
          val orgSkeleton = Organization(ownerId = request.requesterId, name = request.initialValues.name, handle = None)
          val orgTemplate = organizationWithModifications(orgSkeleton, request.initialValues.asOrganizationModifications)
          val org = handleCommander.autoSetOrganizationHandle(orgRepo.save(orgTemplate)) getOrElse {
            throw new Exception(OrganizationFail.HANDLE_UNAVAILABLE.message)
          }
          orgMembershipRepo.save(org.newMembership(userId = request.requesterId, role = OrganizationRole.OWNER))
          Some(OrganizationCreateResponse(request, org))
        }
      }
    } match {
      case Success(Some(response)) => Right(response)
      case Success(None) => Left(OrganizationFail.BAD_PARAMETERS)
      case Failure(ex) => Left(OrganizationFail.HANDLE_UNAVAILABLE)
    }
  }

  def modifyOrganization(request: OrganizationModifyRequest): Either[OrganizationFail, OrganizationModifyResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case None =>
          val org = orgRepo.get(request.orgId)

          val modifiedOrg = organizationWithModifications(org, request.modifications)
          if (request.modifications.basePermissions.nonEmpty) {
            val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
            applyNewBasePermissionsToMembers(memberships, org.basePermissions, modifiedOrg.basePermissions)
          }

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

  def deleteOrganization(request: OrganizationDeleteRequest): Either[OrganizationFail, OrganizationDeleteResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case None =>
          val org = orgRepo.get(request.orgId)

          val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
          memberships.foreach { membership => orgMembershipRepo.deactivate(membership) }

          val invites = orgInviteRepo.getAllByOrganization(org.id.get)
          invites.foreach { invite => orgInviteRepo.deactivate(invite.id.get) }

          orgRepo.save(org.sanitizeForDelete)
          handleCommander.reclaimAll(org.id.get, overrideProtection = true, overrideLock = true)
          Right(OrganizationDeleteResponse(request))
        case Some(orgFail) => Left(orgFail)
      }
    }
  }

  def transferOrganization(request: OrganizationTransferRequest): Either[OrganizationFail, OrganizationTransferResponse] = {
    db.readWrite { implicit session =>
      getValidationError(request) match {
        case Some(orgFail) => Left(orgFail)
        case None =>
          val org = orgRepo.get(request.orgId)
          val newOwnerMembership = orgMembershipRepo.getByOrgIdAndUserId(org.id.get, request.newOwner) match {
            case None => orgMembershipRepo.save(org.newMembership(request.newOwner, OrganizationRole.OWNER))
            case Some(membership) => orgMembershipRepo.save(org.modifiedMembership(membership, newRole = OrganizationRole.OWNER))
          }
          val modifiedOrg = orgRepo.save(org.withOwner(request.newOwner))
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
}
