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
    val org = orgRepo.get(orgId)
    userIdOpt match {
      case None => org.getNonmemberPermissions
      case Some(userId) =>
        orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).map(_.permissions) getOrElse {
          val invites = orgInviteRepo.getByOrgIdAndUserId(orgId, userId)
          if (invites.isEmpty) org.getNonmemberPermissions
          else org.getNonmemberPermissions + OrganizationPermission.VIEW_ORGANIZATION
        }
    }
  }

  def libraryPermissionsByAccess(library: Library, access: LibraryAccess): Set[LibraryPermission] = access match {
    case LibraryAccess.READ_ONLY => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.INVITE_FOLLOWERS
    )

    case LibraryAccess.READ_WRITE => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.INVITE_FOLLOWERS,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS
    ) ++ (if (!library.whoCanInvite.contains(LibraryInvitePermissions.OWNER)) Set(LibraryPermission.INVITE_COLLABORATORS) else Set.empty)

    case LibraryAccess.OWNER if library.isSystemCreated => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS
    )
    case LibraryAccess.OWNER if library.isUserCreated => Set(
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
  def libraryPermissionsFromOrgPermissions(lib: Library, orgPermissions: Set[OrganizationPermission]): Set[LibraryPermission] = orgPermissions.collect {
    case OrganizationPermission.REMOVE_LIBRARIES => Set(LibraryPermission.DELETE_LIBRARY, LibraryPermission.MOVE_LIBRARY)
    case OrganizationPermission.FORCE_EDIT_LIBRARIES => Set(LibraryPermission.EDIT_LIBRARY)
    case OrganizationPermission.EXPORT_KEEPS => Set(LibraryPermission.EXPORT_KEEPS)
    case OrganizationPermission.CREATE_SLACK_INTEGRATION => Set(LibraryPermission.CREATE_SLACK_INTEGRATION)
  }.flatten

  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission] = {
    val lib = libraryRepo.get(libId)
    val libMembershipOpt = userIdOpt.flatMap { userId => libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId) }

    libMembershipOpt match {
      case Some(libMembership) =>
        val libPermissions = libraryPermissionsByAccess(lib, libMembership.access)
        val implicitPermissions = lib.organizationId.map { orgId =>
          libraryPermissionsFromOrgPermissions(lib, getOrganizationPermissions(orgId, userIdOpt))
        }.getOrElse(Set.empty)
        libPermissions ++ implicitPermissions
      case None =>
        val libraryCanBeViewed = lib.visibility match {
          case LibraryVisibility.PUBLISHED => true
          case LibraryVisibility.SECRET => false
          case LibraryVisibility.DISCOVERABLE =>
            userIdOpt.contains(lib.ownerId)
          case LibraryVisibility.ORGANIZATION =>
            userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(lib.organizationId.get, _).isDefined)
        }
        val userHasInvite = userIdOpt.exists { userId => libraryInviteRepo.getWithLibraryIdAndUserId(libId, userId).nonEmpty }
        if (userHasInvite || libraryCanBeViewed) libraryPermissionsByAccess(lib, LibraryAccess.READ_ONLY)
        else Set.empty
    }
  }
}
