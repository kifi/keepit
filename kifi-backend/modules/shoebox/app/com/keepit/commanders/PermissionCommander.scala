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
import com.keepit.common.core.anyExtensionOps
import com.keepit.payments.PaidAccount
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Random

@ImplementedBy(classOf[PermissionCommanderImpl])
trait PermissionCommander {
  def getOrganizationsPermissions(orgIds: Set[Id[Organization]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Organization], Set[OrganizationPermission]]
  def getOrganizationPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission]
  def getLibrariesPermissions(libIds: Set[Id[Library]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Library], Set[LibraryPermission]]
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
    val orgMemberships = orgMembershipRepo.getAllByUserId(userId).toSet
    val permissionsViaOrgMembership = orgMemberships.flatMap { mem =>
      userPermissionsFromOrganization(mem.organizationId)
    }
    val personalPermissions: Set[UserPermission] = Set.empty // Right now there is no way for a user to get their own UserPermissions

    personalPermissions ++ permissionsViaOrgMembership
  }
  private def userPermissionsFromOrganization(orgId: Id[Organization])(implicit session: RSession): Set[UserPermission] = {
    Set(UserPermission.CREATE_SLACK_INTEGRATION) // Right now, any org membership will grant a user permission to create Slack integrations
  }

  @StatsdTiming("PermissionCommander.getLibraryPermissions")
  def getLibraryPermissions(libId: Id[Library], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[LibraryPermission] = {
    getLibrariesPermissions(Set(libId), userIdOpt).get(libId).get
  }

  @StatsdTiming("PermissionCommander.getLibrariesPermissions")
  def getLibrariesPermissions(libIds: Set[Id[Library]], userIdOpt: Option[Id[User]])(implicit session: RSession): Map[Id[Library], Set[LibraryPermission]] = {
    val libsById = libraryRepo.getActiveByIds(libIds)
    val libMembershipsById = userIdOpt.map { userId =>
      libraryMembershipRepo.getWithLibraryIdsAndUserId(libIds, userId)
    }.getOrElse(Map.empty.withDefaultValue(None))
    val invitesById = userIdOpt.map { userId =>
      libraryInviteRepo.getWithLibraryIdsAndUserId(libIds, userId)
    }.getOrElse(Map.empty.withDefaultValue(Seq.empty))
    val orgIdsByLibraryId = libsById.map { case (libId, lib) => libId -> lib.organizationId }
    val orgIds = orgIdsByLibraryId.values.flatten.toSet
    val orgMembershipsById = userIdOpt.map { userId =>
      orgMembershipRepo.getByOrgIdsAndUserId(orgIds, userId)
    }.getOrElse(Map.empty.withDefaultValue(None))

    val libAccessById = libMembershipsById.map {
      case (libId, memOpt) => memOpt match {
        case Some(libMembership) => libId -> Some(libMembership.access)
        case None =>
          val lib = libsById(libId)
          val viewerHasImplicitAccess = lib.visibility match {
            case LibraryVisibility.DISCOVERABLE =>
              val isOwner = userIdOpt.contains(lib.ownerId) // this would be a really bad sign, since they should have a LibraryMembership
              if (isOwner) airbrake.notify(s"found a library owner without a library membership! (libId=$libId, userId=$userIdOpt)")
              isOwner
            case LibraryVisibility.ORGANIZATION =>
              lib.organizationId.exists(orgId => orgMembershipsById(orgId).isDefined)
            case _ => false
          }
          val userHasInvite = invitesById.get(libId).exists(_.nonEmpty)
          libId -> Some(LibraryAccess.READ_ONLY).filter { _ => viewerHasImplicitAccess || userHasInvite }
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
    libsById.map {
      case (libId, lib) =>
        val basePermissions = libraryPermissionsByAccess(libsById(libId), libAccessById(libId))
        val withUser = mixInUserPermissions(lib, basePermissions)
        val withOrg = mixInOrgPermisisons(lib, withUser)
        libId -> withOrg
    }
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
