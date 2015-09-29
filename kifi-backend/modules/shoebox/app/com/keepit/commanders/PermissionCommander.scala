package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._

import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[PermissionCommanderImpl])
trait PermissionCommander {
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission]
}

@Singleton
class PermissionCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgInviteRepo: OrganizationInviteRepo,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    userExperimentRepo: UserExperimentRepo,
    orgExperimentRepo: OrganizationExperimentRepo,
    implicit val executionContext: ExecutionContext) extends PermissionCommander with Logging {

  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
    // TODO(ryan): this needs to look in orgPermissionsCache by (orgId, userIdOpt) to see if the permissions are cached
    // If not, it should compute them directly and add them to the cache
    computeOrganizationPermissions(orgId, userIdOpt)
  }

  private def computeOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
    val roleOpt = userIdOpt.flatMap { userId => orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).map(_.role) }
    val basePermissions = settinglessOrganizationPermissions(roleOpt)
    val hasOrganizationInvite = userIdOpt.exists { userId => orgInviteRepo.getByOrgIdAndUserId(orgId, userId).nonEmpty }

    val inviteBasedPermissions = if (hasOrganizationInvite) extraInviteePermissions else Set.empty
    val settingsBasedPermissions = orgConfigRepo.getByOrgId(orgId).settings.extraPermissionsFor(roleOpt)

    basePermissions ++ inviteBasedPermissions ++ settingsBasedPermissions
  }

  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission] = {
    val lib = libraryRepo.get(libId)
    val libMembershipOpt = userIdOpt.flatMap { userId => libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId) }

    val libAccessOpt = libMembershipOpt match {
      case Some(libMembership) => Some(libMembership.access)
      case None =>
        val viewerHasImplicitAccess = lib.visibility match {
          case LibraryVisibility.DISCOVERABLE =>
            userIdOpt.contains(lib.ownerId) // this would be a really bad sign, since they should have a LibraryMembership
          case LibraryVisibility.ORGANIZATION =>
            userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(lib.organizationId.get, _).isDefined)
          case _ => false
        }
        val userHasInvite = userIdOpt.exists { userId => libraryInviteRepo.getWithLibraryIdAndUserId(libId, userId).nonEmpty }
        Some(LibraryAccess.READ_ONLY).filter { _ => viewerHasImplicitAccess || userHasInvite }
    }

    val libPermissions = libraryPermissionsByAccess(lib, libAccessOpt)
    lib.organizationId.map { orgId =>
      combineOrganizationAndLibraryPermissions(libPermissions, getOrganizationPermissions(orgId, userIdOpt))
    } getOrElse libPermissions
  }

  def libraryPermissionsByAccess(library: Library, accessOpt: Option[LibraryAccess]): Set[LibraryPermission] = accessOpt match {
    case None =>
      if (library.isPublished) Set(LibraryPermission.VIEW_LIBRARY) else Set.empty
    case Some(LibraryAccess.READ_ONLY) => Set(
      LibraryPermission.VIEW_LIBRARY
    ) ++ (if (!library.isSecret) Set(LibraryPermission.INVITE_FOLLOWERS) else Set.empty)

    case Some(LibraryAccess.READ_WRITE) => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.INVITE_FOLLOWERS,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS
    ) ++ (if (!library.whoCanInvite.contains(LibraryInvitePermissions.OWNER)) Set(LibraryPermission.INVITE_COLLABORATORS) else Set.empty)

    case Some(LibraryAccess.OWNER) if library.isSystemLibrary => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS
    )
    case Some(LibraryAccess.OWNER) if library.canBeModified => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.EDIT_LIBRARY,
      LibraryPermission.MOVE_LIBRARY,
      LibraryPermission.DELETE_LIBRARY,
      LibraryPermission.REMOVE_MEMBERS,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS,
      LibraryPermission.REMOVE_OTHER_KEEPS,
      LibraryPermission.INVITE_FOLLOWERS,
      LibraryPermission.INVITE_COLLABORATORS
    )
  }
  def combineOrganizationAndLibraryPermissions(libPermissions: Set[LibraryPermission], orgPermissions: Set[OrganizationPermission]): Set[LibraryPermission] = {
    val addedPermissions = Map[Boolean, Set[LibraryPermission]](
      (libPermissions.contains(LibraryPermission.VIEW_LIBRARY) && orgPermissions.contains(OrganizationPermission.FORCE_EDIT_LIBRARIES)) ->
        Set(LibraryPermission.EDIT_LIBRARY, LibraryPermission.MOVE_LIBRARY)
    ).collect { case (true, ps) => ps }.flatten

    val removedPermissions = Map[Boolean, Set[LibraryPermission]](
      !orgPermissions.contains(OrganizationPermission.REMOVE_LIBRARIES) ->
        Set(LibraryPermission.MOVE_LIBRARY, LibraryPermission.DELETE_LIBRARY)
    ).collect { case (true, ps) => ps }.flatten

    libPermissions ++ addedPermissions -- removedPermissions
  }

  val extraInviteePermissions: Set[OrganizationPermission] = Set(OrganizationPermission.VIEW_ORGANIZATION)
  def settinglessOrganizationPermissions(orgRoleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = orgRoleOpt match {
    case None => Set(
      OrganizationPermission.VIEW_ORGANIZATION
    )
    case Some(OrganizationRole.MEMBER) => Set(
      OrganizationPermission.VIEW_ORGANIZATION,
      OrganizationPermission.ADD_LIBRARIES
    )
    case Some(OrganizationRole.ADMIN) => Set(
      OrganizationPermission.VIEW_ORGANIZATION,
      OrganizationPermission.MODIFY_MEMBERS,
      OrganizationPermission.REMOVE_MEMBERS,
      OrganizationPermission.ADD_LIBRARIES,
      OrganizationPermission.MANAGE_PLAN
    )
  }

}
