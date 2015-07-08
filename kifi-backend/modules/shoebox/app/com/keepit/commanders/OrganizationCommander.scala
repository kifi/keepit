package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.controller.UserRequest
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.store.ImageSize
import com.keepit.model.OrganizationPermission.{ EDIT_ORGANIZATION, VIEW_ORGANIZATION }
import com.keepit.model._

import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def getAllOrganizationIds: Seq[Id[Organization]]
  def getOrganizationView(orgId: Id[Organization]): OrganizationView
  def getOrganizationCards(orgIds: Seq[Id[Organization]]): Map[Id[Organization], OrganizationCard]
  def isValidRequest(request: OrganizationRequest)(implicit session: RSession): Boolean
  def createOrganization(request: OrganizationCreateRequest): Either[OrganizationFail, OrganizationCreateResponse]
  def modifyOrganization(request: OrganizationModifyRequest): Either[OrganizationFail, OrganizationModifyResponse]
  def deleteOrganization(request: OrganizationDeleteRequest): Either[OrganizationFail, OrganizationDeleteResponse]
  def transferOrganization(request: OrganizationTransferRequest): Either[OrganizationFail, OrganizationTransferResponse]

  def unsafeModifyOrganization(request: UserRequest[_], orgId: Id[Organization], modifications: OrganizationModifications): Unit

  def getAnalyticsView(orgId: Id[Organization]): AnalyticsOrganizationView
  def getAnalyticsCards(orgIds: Seq[Id[Organization]]): Map[Id[Organization], AnalyticsOrganizationCard]
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
    implicit val publicIdConfig: PublicIdConfiguration,
    handleCommander: HandleCommander) extends OrganizationCommander with Logging {

  // TODO(ryan): do the smart thing and add a limit/offset
  def getAllOrganizationIds: Seq[Id[Organization]] = db.readOnlyReplica { implicit session => orgRepo.all().map(_.id.get) }

  def getOrganizationView(orgId: Id[Organization]): OrganizationView = {
    db.readOnlyReplica { implicit session => getOrganizationViewHelper(orgId) }
  }
  def getOrganizationCards(orgIds: Seq[Id[Organization]]): Map[Id[Organization], OrganizationCard] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map { orgId => orgId -> getOrganizationCardHelper(orgId) }.toMap
    }
  }
  private def getOrganizationViewHelper(orgId: Id[Organization])(implicit session: RSession): OrganizationView = {
    val org = orgRepo.get(orgId)
    val orgHandle = org.getHandle
    val orgName = org.name
    val description = org.description

    val members = orgMembershipRepo.getByOrgId(orgId, Limit(8), Offset(0)).map(_.userId)
    val memberCount = orgMembershipRepo.countByOrgId(orgId)
    val libraries = libraryRepo.countLibrariesForOrgByVisibility(orgId)

    val avatarPath = organizationAvatarCommander.getBestImage(orgId, ImageSize(200, 200)).map(_.imagePath)

    // TODO(ryan): actually find the number of libraries
    val numPublicLibs = libraries(LibraryVisibility.PUBLISHED)
    OrganizationView(orgId = orgId, handle = orgHandle, name = orgName, description = description, avatarPath = avatarPath, ownerId = org.ownerId, members = members,
      numMembers = memberCount, numLibraries = numPublicLibs)
  }

  private def getOrganizationCardHelper(orgId: Id[Organization])(implicit session: RSession): OrganizationCard = {
    val org = orgRepo.get(orgId)
    val orgHandle = org.getHandle
    val orgName = org.name
    val description = org.description

    val numMembers = orgMembershipRepo.countByOrgId(orgId)
    val libraries = libraryRepo.countLibrariesForOrgByVisibility(orgId)

    val avatarPath = organizationAvatarCommander.getBestImage(orgId, ImageSize(200, 200)).map(_.imagePath)

    // TODO(ryan): actually find the number of libraries
    val numPublicLibs = libraries(LibraryVisibility.PUBLISHED)
    OrganizationCard(orgId = orgId, handle = orgHandle, name = orgName, description = description, avatarPath = avatarPath, numMembers = numMembers, numLibraries = numPublicLibs)
  }

  def isValidRequest(request: OrganizationRequest)(implicit session: RSession): Boolean = {
    getValidationError(request).isEmpty
  }

  def getValidationError(request: OrganizationRequest)(implicit session: RSession): Option[OrganizationFail] = {
    request match {
      case OrganizationCreateRequest(createrId, initialParameters) =>
        if (initialParameters.name.isEmpty || !areAllValidModifications(initialParameters)) {
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
          val protoOrg = Organization(ownerId = request.requesterId, name = request.initialValues.name.get, handle = None)
          val orgTemplate = organizationWithModifications(protoOrg, request.initialValues)
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
    if (!request.experiments.contains(ExperimentType.ADMIN)) {
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

  def getAnalyticsView(orgId: Id[Organization]): AnalyticsOrganizationView = {
    db.readOnlyReplica { implicit session =>
      val orgView = getOrganizationViewHelper(orgId)
      val memberIds = orgMembershipRepo.getAllByOrgId(orgId).map(_.userId)
      val numTotalKeeps = keepRepo.getCountByUsers(memberIds).values.sum
      // TODO(ryan): get actual numbers for chats
      val numTotalChats = 420
      val memberInfos = orgMembershipCommander.getMembersInfo(orgId)
      AnalyticsOrganizationView(orgView, AnalyticsOrganizationViewExtras(numTotalKeeps, numTotalChats, memberInfos))
    }
  }

  def getAnalyticsCards(orgIds: Seq[Id[Organization]]): Map[Id[Organization], AnalyticsOrganizationCard] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map { orgId =>
        val orgCard = getOrganizationCardHelper(orgId)
        val numTotalKeeps = 0
        val numTotalChats = 0
        orgId -> AnalyticsOrganizationCard(orgCard, AnalyticsOrganizationCardExtras(numTotalKeeps, numTotalChats))
      }.toMap
    }
  }

}
