package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.Id
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.performance.StatsdTiming
import com.keepit.model._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Random

@ImplementedBy(classOf[PermissionCommanderImpl])
trait PermissionCommander {
  def getOrganizationsPermissions(orgIds: Set[Id[Organization]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Organization], Set[OrganizationPermission]]
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]

  def getLibrariesPermissions(libIds: Set[Id[Library]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Library], Set[LibraryPermission]]
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission]

  def getKeepsPermissions(keepIds: Set[Id[Keep]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Keep], Set[KeepPermission]]
  def getKeepPermissions(keepId: Id[Keep], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[KeepPermission]

  def partitionLibrariesUserCanJoinOrWriteTo(userId: Id[User], libIds: Set[Id[Library]])(implicit session: RSession): (Set[Id[Library]], Set[Id[Library]], Set[Id[Library]])
  def canJoinOrWriteToLibrary(userId: Id[User], libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Boolean]
}

@Singleton
class PermissionCommanderImpl @Inject() (
    orgPermissionsCache: OrganizationPermissionsCache,
    orgPermissionsNamespaceCache: OrganizationPermissionsNamespaceCache,
    orgRepo: OrganizationRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgInviteRepo: OrganizationInviteRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    libraryInviteRepo: LibraryInviteRepo,
    keepRepo: KeepRepo,
    userRepo: UserRepo,
    ktlRepo: KeepToLibraryRepo,
    ktuRepo: KeepToUserRepo,
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends PermissionCommander with Logging {

  @StatsdTiming("PermissionCommander.getOrganizationsPermissions")
  def getOrganizationsPermissions(orgIds: Set[Id[Organization]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Organization], Set[OrganizationPermission]] = {
    /*
    PSA regarding these caches:
    We want to have a cache from (Id[Organization], Option[Id[User]]) => Set[OrganizationPermission]
    When an organization is modified, we need to invalidate all of the keys that have that org's
    ID in them. There is no easy way to do this via a single cache. Instead, we use two caches,
        1. Id[Organization] => Int (we call this Int a "namespace", because it controls the key namespace in cache 2)
        2. (Id[Organization, Int, Option[Id[User]]) => Set[OrganizationPermission]
    With two caches, we can invalidate an entire organization by changing its value in cache 1
    */
    val orgPermissionsNamespace = orgPermissionsNamespaceCache.bulkGetOrElse(orgIds.map(OrganizationPermissionsNamespaceKey)) { missingKeys =>
      missingKeys.map { mk => mk -> Random.nextInt() }.toMap
    }.map { case (key, value) => key.orgId -> value }

    orgPermissionsCache.bulkGetOrElse(orgIds.map(orgId => OrganizationPermissionsKey(orgId, orgPermissionsNamespace(orgId), userIdOpt))) { missingKeys =>
      missingKeys.map { mk => mk -> computeOrganizationPermissions(mk.orgId, userIdOpt) }.toMap
    }.map { case (key, value) => key.orgId -> value }
  }

  @StatsdTiming("PermissionCommander.getOrganizationPermissions")
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
    getOrganizationsPermissions(Set(orgId), userIdOpt).get(orgId).get
  }

  private def computeOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
    val org = orgRepo.get(orgId)
    if (org.isInactive) Set.empty
    else {
      val roleOpt = userIdOpt.flatMap { userId => orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).map(_.role) }
      val basePermissions = settinglessOrganizationPermissions(roleOpt)
      val hasOrganizationInvite = userIdOpt.exists { userId => orgInviteRepo.getByOrgIdAndUserId(orgId, userId).nonEmpty }

      val inviteBasedPermissions = if (hasOrganizationInvite) extraInviteePermissions else Set.empty
      val settingsBasedPermissions = orgConfigRepo.getByOrgId(orgId).settings.extraPermissionsFor(roleOpt)

      basePermissions ++ inviteBasedPermissions ++ settingsBasedPermissions
    }
  }

  private def computeUserPermissions(userId: Id[User])(implicit session: RSession): Set[UserPermission] = {
    Set(UserPermission.CREATE_SLACK_INTEGRATION) // ALL users get this permission by default
  }

  @StatsdTiming("PermissionCommander.getLibraryPermissions")
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission] = {
    getLibrariesPermissions(Set(libId), userIdOpt).getOrElse(libId, Set.empty)
  }

  @StatsdTiming("PermissionCommander.getLibrariesPermissions")
  def getLibrariesPermissions(libIds: Set[Id[Library]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Library], Set[LibraryPermission]] = {
    val libsById = libraryRepo.getActiveByIds(libIds)
    val libMembershipsById = userIdOpt.fold(Map.empty[Id[Library], LibraryMembership]) { userId =>
      libraryMembershipRepo.getWithLibraryIdsAndUserId(libIds, userId)
    }
    val invitesById = userIdOpt.map { userId =>
      libraryInviteRepo.getWithLibraryIdsAndUserId(libIds, userId)
    }.getOrElse(libIds.map(_ -> Seq.empty).toMap)
    val orgIdsByLibraryId = libsById.map { case (libId, lib) => libId -> lib.organizationId }
    val orgIds = orgIdsByLibraryId.values.flatten.toSet
    val orgMembershipsById = userIdOpt.map { userId =>
      orgMembershipRepo.getByOrgIdsAndUserId(orgIds, userId)
    }.getOrElse(orgIds.map(_ -> None).toMap)

    val libAccessById = libsById.map {
      case (libId, lib) =>
        libId -> libMembershipsById.get(libId).map(_.access).orElse {
          val viewerHasImplicitAccess = lib.visibility match {
            case LibraryVisibility.DISCOVERABLE =>
              val isOwner = userIdOpt.contains(lib.ownerId) // this would be a really bad sign, since they should have a LibraryMembership
              if (isOwner) airbrake.notify(s"found a library owner without a library membership! (libId=$libId, userId=$userIdOpt)")
              isOwner
            case LibraryVisibility.ORGANIZATION =>
              lib.organizationId.exists(orgId => orgMembershipsById(orgId).isDefined)
            case _ => false
          }
          val userHasInvite = invitesById(libId).nonEmpty
          Some(LibraryAccess.READ_ONLY).filter { _ => viewerHasImplicitAccess || userHasInvite }
        }
    }

    val userPermissions = userIdOpt.map(computeUserPermissions).getOrElse(Set.empty)
    val orgPermissionsById = getOrganizationsPermissions(orgIds, userIdOpt)

    def mixInUserPermissions(lib: Library, libPermissions: Set[LibraryPermission]) = {
      userIdOpt.map { userId =>
        combineUserAndLibraryPermissions(lib, libPermissions, userPermissions)
      } getOrElse libPermissions
    }
    def mixInOrgPermisisons(lib: Library, libPermissions: Set[LibraryPermission]) = {
      lib.organizationId.map { orgId =>
        combineOrganizationAndLibraryPermissions(lib, libPermissions, orgPermissionsById(orgId))
      } getOrElse libPermissions
    }

    def hasOrgWriteAccess(library: Library): Boolean = {
      library.organizationMemberAccess.contains(LibraryAccess.READ_WRITE) &&
        library.organizationId.exists(orgMembershipsById.get(_).flatten.isDefined)
    }

    libsById.map {
      case (libId, lib) =>
        val basePermissions = libraryPermissionsByAccess(lib, libAccessById.getOrElse(libId, None), hasOrgWriteAccess(lib))
        val withUser = mixInUserPermissions(lib, basePermissions)
        val withOrg = mixInOrgPermisisons(lib, withUser)
        libId -> withOrg
    }
  }

  def libraryPermissionsByAccess(library: Library, accessOpt: Option[LibraryAccess], includeOrgWriteAccess: Boolean): Set[LibraryPermission] = accessOpt match {
    case None =>
      Set(LibraryPermission.VIEW_LIBRARY).filter(_ => library.isPublished) ++
        Set(LibraryPermission.ADD_COMMENTS).filter(_ => (library.isPublished && library.canAnyoneComment) || includeOrgWriteAccess)
    case Some(LibraryAccess.READ_ONLY) =>
      Set(LibraryPermission.VIEW_LIBRARY) ++
        Set(LibraryPermission.INVITE_FOLLOWERS).filter(_ => !library.isSecret) ++
        Set(LibraryPermission.ADD_COMMENTS).filter(_ => library.canAnyoneComment || includeOrgWriteAccess)

    case Some(LibraryAccess.OWNER) | Some(LibraryAccess.READ_WRITE) if library.isSystemLibrary => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.EDIT_OTHER_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS,
      LibraryPermission.ADD_COMMENTS
    )

    case Some(LibraryAccess.READ_WRITE) if library.canBeModified => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.INVITE_FOLLOWERS,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.EDIT_OTHER_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS,
      LibraryPermission.REMOVE_OTHER_KEEPS,
      LibraryPermission.ADD_COMMENTS
    ) ++ (if (!library.whoCanInvite.contains(LibraryInvitePermissions.OWNER)) Set(LibraryPermission.INVITE_COLLABORATORS) else Set.empty)

    case Some(LibraryAccess.OWNER) if library.canBeModified => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.EDIT_LIBRARY,
      LibraryPermission.MOVE_LIBRARY,
      LibraryPermission.DELETE_LIBRARY,
      LibraryPermission.REMOVE_MEMBERS,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.EDIT_OTHER_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS,
      LibraryPermission.REMOVE_OTHER_KEEPS,
      LibraryPermission.INVITE_FOLLOWERS,
      LibraryPermission.INVITE_COLLABORATORS,
      LibraryPermission.ADD_COMMENTS
    )
  }

  private def combineOrganizationAndLibraryPermissions(lib: Library, libPermissions: Set[LibraryPermission], orgPermissions: Set[OrganizationPermission]): Set[LibraryPermission] = {
    val addedPermissions: Set[LibraryPermission] = {
      val canModifyLibrary = lib.canBeModified && libPermissions.contains(LibraryPermission.VIEW_LIBRARY)
      val canForceEdit = canModifyLibrary && orgPermissions.contains(OrganizationPermission.FORCE_EDIT_LIBRARIES)
      Set(
        canForceEdit -> Set(LibraryPermission.EDIT_LIBRARY, LibraryPermission.MOVE_LIBRARY, LibraryPermission.DELETE_LIBRARY)
      ).collect { case (true, ps) => ps }.flatten
    }

    val removedPermissions: Set[LibraryPermission] = {
      val cannotRemoveLibraries = !orgPermissions.contains(OrganizationPermission.REMOVE_LIBRARIES)
      val cannotCreateSlackIntegrations = !orgPermissions.contains(OrganizationPermission.CREATE_SLACK_INTEGRATION)
      Set(
        cannotRemoveLibraries -> Set(LibraryPermission.MOVE_LIBRARY),
        cannotCreateSlackIntegrations -> Set(LibraryPermission.CREATE_SLACK_INTEGRATION)
      ).collect { case (true, ps) => ps }.flatten
    }

    libPermissions ++ addedPermissions -- removedPermissions
  }
  private def combineUserAndLibraryPermissions(lib: Library, libPermissions: Set[LibraryPermission], userPermissions: Set[UserPermission]): Set[LibraryPermission] = {
    val addedPermissions: Set[LibraryPermission] = {
      val canViewLibrary = libPermissions.contains(LibraryPermission.VIEW_LIBRARY)
      val canCreateSlackIntegration = canViewLibrary && userPermissions.contains(UserPermission.CREATE_SLACK_INTEGRATION)
      Set(
        canCreateSlackIntegration -> Set(LibraryPermission.CREATE_SLACK_INTEGRATION)
      ).collect { case (true, ps) => ps }.flatten
    }

    val removedPermissions: Set[LibraryPermission] = Set.empty

    libPermissions ++ addedPermissions -- removedPermissions
  }

  val extraInviteePermissions: Set[OrganizationPermission] = Set(OrganizationPermission.VIEW_ORGANIZATION)
  private def settinglessOrganizationPermissions(orgRoleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = orgRoleOpt match {
    case None => Set.empty
    case Some(OrganizationRole.MEMBER) => Set(
      OrganizationPermission.ADD_LIBRARIES,
      OrganizationPermission.REDEEM_CREDIT_CODE
    )
    case Some(OrganizationRole.ADMIN) => Set(
      OrganizationPermission.ADD_LIBRARIES,
      OrganizationPermission.MANAGE_PLAN,
      OrganizationPermission.MODIFY_MEMBERS,
      OrganizationPermission.REDEEM_CREDIT_CODE,
      OrganizationPermission.REMOVE_MEMBERS
    )
  }

  def getKeepsPermissions(keepIds: Set[Id[Keep]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Keep], Set[KeepPermission]] = {
    val librariesByKeep = ktlRepo.getAllByKeepIds(keepIds).map { case (kid, ktls) => kid -> ktls.map(_.libraryId).toSet }
    val usersByKeep = ktuRepo.getAllByKeepIds(keepIds).map { case (kid, ktus) => kid -> ktus.map(_.userId).toSet }

    val libIds = librariesByKeep.values.flatten.toSet
    val libraries = libraryRepo.getActiveByIds(libIds)
    val libPermissions = getLibrariesPermissions(libIds, userIdOpt)
    val keeps = keepRepo.getActiveByIds(keepIds)

    keeps.map {
      case (kId, k) =>
        val keepLibraries = librariesByKeep.getOrElse(kId, Set.empty)
        val keepUsers = usersByKeep.getOrElse(kId, Set.empty)
        val viewerIsDirectlyConnectedToKeep = userIdOpt.exists(keepUsers.contains)

        val canAddMessage = {
          val viewerCanAddMessageViaLibrary = keepLibraries.exists { libId =>
            libPermissions.getOrElse(libId, Set.empty).contains(LibraryPermission.ADD_COMMENTS)
          }
          viewerIsDirectlyConnectedToKeep || viewerCanAddMessageViaLibrary
        }
        val canAddLibraries = canAddMessage
        val canAddParticipants = canAddMessage
        val canDeleteOwnMessages = true
        val canDeleteOtherMessages = {
          val viewerOwnsTheKeep = userIdOpt containsTheSameValueAs k.userId
          // This seems like a pretty strange operational definition...
          val viewerOwnsOneOfTheKeepLibraries = keepLibraries.flatMap(libraries.get).exists(lib => userIdOpt.safely.contains(lib.ownerId))
          viewerOwnsTheKeep || viewerOwnsOneOfTheKeepLibraries
        }
        val canRemoveLibraries = userIdOpt containsTheSameValueAs k.userId
        val canViewKeep = {
          // TODO(ryan): remove deprecated permissions when more confident they're unnecessary
          val deprecatedPermissions = userIdOpt.containsTheSameValueAs(k.userId) || userIdOpt.containsTheSameValueAs(k.originalKeeperId)

          val viewerCanSeeKeepViaLibrary = keepLibraries.exists { libId =>
            libPermissions.getOrElse(libId, Set.empty).contains(LibraryPermission.VIEW_LIBRARY)
          }
          deprecatedPermissions || viewerIsDirectlyConnectedToKeep || viewerCanSeeKeepViaLibrary
        }
        val canEditKeep = {
          (userIdOpt containsTheSameValueAs k.userId) || keepLibraries.flatMap(libPermissions.getOrElse(_, Set.empty)).contains(LibraryPermission.EDIT_OTHER_KEEPS)
        }
        val canDeleteKeep = userIdOpt.containsTheSameValueAs(k.userId)

        kId -> List(
          canAddLibraries -> KeepPermission.ADD_LIBRARIES,
          canRemoveLibraries -> KeepPermission.REMOVE_LIBRARIES,
          canAddParticipants -> KeepPermission.ADD_PARTICIPANTS,
          canAddMessage -> KeepPermission.ADD_MESSAGE,
          canDeleteOwnMessages -> KeepPermission.DELETE_OWN_MESSAGES,
          canDeleteOtherMessages -> KeepPermission.DELETE_OTHER_MESSAGES,
          canViewKeep -> KeepPermission.VIEW_KEEP,
          canEditKeep -> KeepPermission.EDIT_KEEP,
          canDeleteKeep -> KeepPermission.DELETE_KEEP
        ).collect { case (true, p) => p }.toSet[KeepPermission]
    }
  }

  def getKeepPermissions(keepId: Id[Keep], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[KeepPermission] = {
    getKeepsPermissions(Set(keepId), userIdOpt).getOrElse(keepId, Set.empty)
  }

  def partitionLibrariesUserCanJoinOrWriteTo(userId: Id[User], libIds: Set[Id[Library]])(implicit session: RSession): (Set[Id[Library]], Set[Id[Library]], Set[Id[Library]]) = {
    val permissionsByLibraryId = getLibrariesPermissions(libIds, Some(userId))
    val (libsUserCanWriteTo, libsUserCannotWriteTo) = libIds.partition { libId => permissionsByLibraryId.get(libId).exists(_.contains(LibraryPermission.ADD_KEEPS)) }
    val (libsUserCanJoin, libsUserCannotJoin) = libsUserCannotWriteTo.partition { libId =>
      val lib = libraryRepo.get(libId)
      val userHasInvite = libraryInviteRepo.getWithLibraryIdAndUserId(libId, userId).exists(inv => LibraryAccess.collaborativePermissions.contains(inv.access))
      val libHasOpenCollaboration = lib.organizationMemberAccess.exists(LibraryAccess.collaborativePermissions.contains) &&
        lib.organizationId.exists(orgId => orgMembershipRepo.getByOrgIdAndUserId(orgId, userId).isDefined)
      userHasInvite || libHasOpenCollaboration
    }
    (libsUserCanWriteTo, libsUserCanJoin, libsUserCannotJoin)
  }

  def canJoinOrWriteToLibrary(userId: Id[User], libIds: Set[Id[Library]])(implicit session: RSession): Map[Id[Library], Boolean] = {
    val (libsUserCanWriteTo, libsUserCanJoin, _) = partitionLibrariesUserCanJoinOrWriteTo(userId, libIds)
    val libsUserCanJoinOrWriteTo = libsUserCanWriteTo ++ libsUserCanJoin
    libIds.map(libId => libId -> libsUserCanJoinOrWriteTo.contains(libId)).toMap
  }
}

case class OrganizationPermissionsNamespaceKey(orgId: Id[Organization]) extends Key[Int] {
  override val version = 5
  val namespace = "org_permissions_namespace"
  def toKey(): String = orgId.id.toString
}
class OrganizationPermissionsNamespaceCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends PrimitiveCacheImpl[OrganizationPermissionsNamespaceKey, Int](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class OrganizationPermissionsKey(orgId: Id[Organization], opn: Int, userIdOpt: Option[Id[User]]) extends Key[Set[OrganizationPermission]] {
  override val version = 1
  val namespace = "org_permissions"
  def toKey(): String = orgId.id.toString + "_" + opn.toString + "_" + userIdOpt.map(x => x.id.toString).getOrElse("none")
}

class OrganizationPermissionsCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationPermissionsKey, Set[OrganizationPermission]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)
