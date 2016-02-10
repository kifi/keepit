package com.keepit.commanders

import com.google.inject.{ Provider, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibrarySpace.{ UserSpace, OrganizationSpace }
import com.keepit.model._

class LibraryAccessCommander @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryMembershipCommanderProvider: Provider[LibraryMembershipCommander],
    organizationMembershipRepo: OrganizationMembershipRepo,
    permissionCommander: PermissionCommander,
    libraryInviteRepo: LibraryInviteRepo) extends Logging {

  def canModifyLibrary(libraryId: Id[Library], userId: Id[User]): Boolean = {
    db.readOnlyReplica { implicit s =>
      val lib = libraryRepo.get(libraryId)
      val libMembershipOpt = libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId)
      def canDirectlyEditLibrary = libMembershipOpt.exists(_.canWrite)
      def canIndirectlyEditLibrary = lib.organizationId.exists { orgId =>
        permissionCommander.getOrganizationPermissions(orgId, Some(userId)).contains(OrganizationPermission.FORCE_EDIT_LIBRARIES)
      }
      canDirectlyEditLibrary || canIndirectlyEditLibrary
    }
  }

  def canMoveTo(userId: Id[User], libId: Id[Library], to: LibrarySpace): Boolean = {
    val userCanModifyLibrary = canModifyLibrary(libId, userId)

    val (canMoveToFromSpace) = db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libId)
      val from: LibrarySpace = library.space
      lazy val canMoveFromSpace = from match {
        case OrganizationSpace(fromOrg) =>
          val fromPermissions = permissionCommander.getOrganizationPermissions(fromOrg, Some(userId))
          (fromPermissions.contains(OrganizationPermission.FORCE_EDIT_LIBRARIES) || (userId == library.ownerId)) && fromPermissions.contains(OrganizationPermission.REMOVE_LIBRARIES)
        case UserSpace(fromUser) => userId == library.ownerId
      }
      lazy val canMoveToSpace = to match {
        case OrganizationSpace(toOrg) => permissionCommander.getOrganizationPermissions(toOrg, Some(userId)).contains(OrganizationPermission.ADD_LIBRARIES)
        case UserSpace(toUser) => toUser == library.ownerId
      }
      val isAlreadyInSpace = library.space == to
      isAlreadyInSpace || (canMoveFromSpace && canMoveToSpace)
    }

    userCanModifyLibrary && canMoveToFromSpace
  }

  def userAccess(userId: Id[User], libraryId: Id[Library]): Option[LibraryAccess] = {
    db.readOnlyMaster { implicit s =>
      val lib = libraryRepo.get(libraryId)
      lib.state match {
        case LibraryStates.ACTIVE =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(libraryId, userId) match {
            case Some(mem) =>
              Some(mem.access)
            case None =>
              if (lib.visibility == LibraryVisibility.PUBLISHED)
                Some(LibraryAccess.READ_ONLY)
              else if (libraryInviteRepo.getWithLibraryIdAndUserId(libraryId, userId).nonEmpty)
                Some(LibraryAccess.READ_ONLY)
              else
                None
          }
        case _ => None
      }
    }
  }

  private def canViewLibraryHelper(userIdOpt: Option[Id[User]], library: Library, authToken: Option[String] = None)(implicit session: RSession): Boolean = {
    library.visibility == LibraryVisibility.PUBLISHED || // published library
      (userIdOpt match {
        case Some(id) =>
          val userIsInLibrary = libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, id).nonEmpty
          def userIsInvitedToLibrary = libraryInviteRepo.getWithLibraryIdAndUserId(userId = id, libraryId = library.id.get).nonEmpty
          def userHasValidAuthToken = getValidLibInvitesFromAuthToken(library.id.get, authToken).nonEmpty
          def userIsInOrg = library.organizationId.flatMap(orgId => organizationMembershipRepo.getByOrgIdAndUserId(orgId, id)).nonEmpty
          val libIsOrgVisible = library.visibility == LibraryVisibility.ORGANIZATION
          userIsInLibrary || userIsInvitedToLibrary || userHasValidAuthToken || (userIsInOrg && libIsOrgVisible)
        case None =>
          getValidLibInvitesFromAuthToken(library.id.get, authToken).nonEmpty
      })
  }

  def canViewLibrary(userIdOpt: Option[Id[User]], library: Library, authToken: Option[String] = None): Boolean = {
    db.readOnlyReplica { implicit session => canViewLibraryHelper(userIdOpt, library, authToken) }
  }

  def canViewLibrary(userId: Option[Id[User]], libraryId: Id[Library], accessToken: Option[String]): Boolean = {
    db.readOnlyReplica { implicit session =>
      val library = libraryRepo.get(libraryId)
      library.state == LibraryStates.ACTIVE && canViewLibraryHelper(userId, library, accessToken)
    }
  }

  def ensureUserCanWriteTo(userId: Id[User], libIds: Set[Id[Library]]): Boolean = {
    // todo: This needs to be rectified with LibraryAccessCommander logic elsewhere (which does not check ADD_KEEPS like this one)
    val (_, libsUserCanJoin, libsUserCannotJoin) = db.readOnlyMaster { implicit session => permissionCommander.partitionLibrariesUserCanJoinOrWriteTo(userId, libIds) }
    if (libsUserCannotJoin.nonEmpty) false
    else {
      implicit val context = HeimdalContext.empty
      libsUserCanJoin.foreach(libId => libraryMembershipCommanderProvider.get.joinLibrary(userId, libId))
      true
    }
  }

  private def getValidLibInvitesFromAuthToken(libraryId: Id[Library], authToken: Option[String])(implicit s: RSession): Seq[LibraryInvite] = {
    if (authToken.nonEmpty) {
      libraryInviteRepo.getByLibraryIdAndAuthToken(libraryId, authToken.get)
    } else {
      Seq.empty[LibraryInvite]
    }
  }

}
