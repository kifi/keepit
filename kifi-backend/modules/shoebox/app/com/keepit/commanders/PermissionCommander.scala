package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ AccessLog, Logging }
import com.keepit.common.performance.StatsdTiming
import com.keepit.model._
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Random

@ImplementedBy(classOf[PermissionCommanderImpl])
trait PermissionCommander {
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission]
}

@Singleton
class PermissionCommanderImpl @Inject() (
    db: Database,
    orgPermissionsCache: OrganizationPermissionsCache,
    orgPermissionsNamespaceCache: OrganizationPermissionsNamespaceCache,
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
    airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends PermissionCommander with Logging {

  @StatsdTiming("PermissionCommander.getOrganizationPermissions")
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
    /*
    PSA regarding these caches:
    We want to have a cache from (Id[Organization], Option[Id[User]]) => Set[OrganizationPermission]
    When an organization is modified, we need to invalidate all of the keys that have that org's
    ID in them. There is no easy way to do this via a single cache. Instead, we use two caches,
        1. Id[Organization] => Int (we call this Int a "namespace", because it controls the key namespace in cache 2)
        2. (Id[Organization, Int, Option[Id[User]]) => Set[OrganizationPermission]
    With two caches, we can invalidate an entire organization by changing its value in cache 1
    */
    val orgPermissionsNamespace = orgPermissionsNamespaceCache.getOrElse(OrganizationPermissionsNamespaceKey(orgId))(Random.nextInt())
    orgPermissionsCache.getOrElse(OrganizationPermissionsKey(orgId, orgPermissionsNamespace, userIdOpt)) {
      computeOrganizationPermissions(orgId, userIdOpt)
    }
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

  @StatsdTiming("PermissionCommander.getLibraryPermissions")
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission] = {
    val lib = libraryRepo.get(libId)
    val libMembershipOpt = userIdOpt.flatMap { userId => libraryMembershipRepo.getWithLibraryIdAndUserId(libId, userId) }

    val libAccessOpt = libMembershipOpt match {
      case Some(libMembership) => Some(libMembership.access)
      case None =>
        val viewerHasImplicitAccess = lib.visibility match {
          case LibraryVisibility.DISCOVERABLE =>
            val isOwner = userIdOpt.contains(lib.ownerId) // this would be a really bad sign, since they should have a LibraryMembership
            if (isOwner) airbrake.notify(s"found a library owner without a library membership! (libId=$libId, userId=$userIdOpt)")
            isOwner
          case LibraryVisibility.ORGANIZATION =>
            userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(lib.organizationId.get, _).isDefined)
          case _ => false
        }
        val userHasInvite = userIdOpt.exists { userId => libraryInviteRepo.getWithLibraryIdAndUserId(libId, userId).nonEmpty }
        Some(LibraryAccess.READ_ONLY).filter { _ => viewerHasImplicitAccess || userHasInvite }
    }

    val libPermissions = libraryPermissionsByAccess(lib, libAccessOpt)
    lib.organizationId.map { orgId =>
      combineOrganizationAndLibraryPermissions(lib, libPermissions, getOrganizationPermissions(orgId, userIdOpt))
    } getOrElse libPermissions
  }

  def libraryPermissionsByAccess(library: Library, accessOpt: Option[LibraryAccess]): Set[LibraryPermission] = accessOpt match {
    case None =>
      if (library.isPublished) Set(LibraryPermission.VIEW_LIBRARY) else Set.empty
    case Some(LibraryAccess.READ_ONLY) => Set(
      LibraryPermission.VIEW_LIBRARY
    ) ++ (if (!library.isSecret) Set(LibraryPermission.INVITE_FOLLOWERS) else Set.empty)

    case Some(LibraryAccess.OWNER) | Some(LibraryAccess.READ_WRITE) if library.isSystemLibrary => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS
    )

    case Some(LibraryAccess.READ_WRITE) if library.canBeModified => Set(
      LibraryPermission.VIEW_LIBRARY,
      LibraryPermission.INVITE_FOLLOWERS,
      LibraryPermission.ADD_KEEPS,
      LibraryPermission.EDIT_OWN_KEEPS,
      LibraryPermission.REMOVE_OWN_KEEPS
    ) ++ (if (!library.whoCanInvite.contains(LibraryInvitePermissions.OWNER)) Set(LibraryPermission.INVITE_COLLABORATORS) else Set.empty)

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

  def combineOrganizationAndLibraryPermissions(lib: Library, libPermissions: Set[LibraryPermission], orgPermissions: Set[OrganizationPermission]): Set[LibraryPermission] = {
    val addedPermissions: Set[LibraryPermission] = {
      val canModifyLibrary = lib.canBeModified && libPermissions.contains(LibraryPermission.VIEW_LIBRARY)
      val canForceEdit = canModifyLibrary && orgPermissions.contains(OrganizationPermission.FORCE_EDIT_LIBRARIES)
      val canCreateSlackIntegration = canModifyLibrary && orgPermissions.contains(OrganizationPermission.CREATE_SLACK_INTEGRATION)
      Set(
        canForceEdit -> Set(LibraryPermission.EDIT_LIBRARY, LibraryPermission.MOVE_LIBRARY, LibraryPermission.DELETE_LIBRARY),
        canCreateSlackIntegration -> Set(LibraryPermission.CREATE_SLACK_INTEGRATION)
      ).collect { case (true, ps) => ps }.flatten
    }

    val removedPermissions: Set[LibraryPermission] = {
      val cannotRemoveLibraries = !orgPermissions.contains(OrganizationPermission.REMOVE_LIBRARIES)
      Set(
        cannotRemoveLibraries -> Set(LibraryPermission.MOVE_LIBRARY)
      ).collect { case (true, ps) => ps }.flatten
    }

    libPermissions ++ addedPermissions -- removedPermissions
  }

  val extraInviteePermissions: Set[OrganizationPermission] = Set(OrganizationPermission.VIEW_ORGANIZATION)
  def settinglessOrganizationPermissions(orgRoleOpt: Option[OrganizationRole]): Set[OrganizationPermission] = orgRoleOpt match {
    case None => Set(
      OrganizationPermission.POKE
    )
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
}

case class OrganizationPermissionsNamespaceKey(orgId: Id[Organization]) extends Key[Int] {
  override val version = 3
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
