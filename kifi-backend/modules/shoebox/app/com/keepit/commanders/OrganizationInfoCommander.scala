package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.performance.StatsdTiming
import com.keepit.common.store.ImageSize
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.slack.SlackInfoCommander
import com.keepit.social.BasicUser
import com.keepit.payments._

import scala.concurrent.{ ExecutionContext }
import scala.util.Try

@ImplementedBy(classOf[OrganizationInfoCommanderImpl])
trait OrganizationInfoCommander {
  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): OrganizationView
  def getOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], OrganizationView]
  def getOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationView
  def getBasicOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): BasicOrganizationView
  def getBasicOrganizationViewsHelper(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): Map[Id[Organization], BasicOrganizationView]
  def getBasicOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): BasicOrganizationView
  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo
  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationInfo]
  def getAccountFeatureSettings(orgId: Id[Organization]): OrganizationSettingsResponse
  def getExternalOrgConfiguration(orgId: Id[Organization]): ExternalOrganizationConfiguration
  def getExternalOrgConfigurationHelper(orgId: Id[Organization])(implicit session: RSession): ExternalOrganizationConfiguration
  def getBasicOrganizationHelper(orgId: Id[Organization])(implicit session: RSession): Option[BasicOrganization]
  def getBasicOrganizations(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], BasicOrganization]
  def getOrgTrackingValues(orgId: Id[Organization]): OrgTrackingValues

}

@Singleton
class OrganizationInfoCommanderImpl @Inject() (
    db: Database,
    permissionCommander: PermissionCommander,
    orgRepo: OrganizationRepo,
    orgConfigRepo: OrganizationConfigurationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgInviteCommander: OrganizationInviteCommander,
    organizationDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    orgInviteRepo: OrganizationInviteRepo,
    organizationAvatarCommander: OrganizationAvatarCommander,
    slackInfoCommander: SlackInfoCommander,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    ktlRepo: KeepToLibraryRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    airbrake: AirbrakeNotifier,
    orgExperimentRepo: OrganizationExperimentRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    planManagementCommander: PlanManagementCommander,
    basicOrganizationIdCache: BasicOrganizationIdCache,
    implicit val executionContext: ExecutionContext) extends OrganizationInfoCommander with Logging {

  def getOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): OrganizationView = {
    db.readOnlyReplica { implicit session => getOrganizationViewHelper(orgId, viewerIdOpt, authTokenOpt) }
  }

  def getOrganizationViews(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Map[Id[Organization], OrganizationView] = {
    db.readOnlyReplica { implicit session => orgIds.map(id => id -> getOrganizationViewHelper(id, viewerIdOpt, authTokenOpt)).toMap }
  }

  def getOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationView = {
    val organizationInfo = getOrganizationInfo(orgId, viewerIdOpt)
    val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
    OrganizationView(organizationInfo, membershipInfo)
  }

  def getBasicOrganizationView(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): BasicOrganizationView = {
    db.readOnlyReplica { implicit session => getBasicOrganizationViewHelper(orgId, viewerIdOpt, authTokenOpt) }
  }

  def getBasicOrganizationViewsHelper(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): Map[Id[Organization], BasicOrganizationView] = {
    orgIds.map(id => id -> getBasicOrganizationViewHelper(id, viewerIdOpt, authTokenOpt)).toMap
  }

  def getBasicOrganizationViewHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): BasicOrganizationView = {
    // This function assumes that the org is active
    val basicOrganization = basicOrganizationIdCache.getOrElse(BasicOrganizationIdKey(orgId))(getBasicOrganizationHelper(orgId).get)
    val membershipInfo = getMembershipInfoHelper(orgId, viewerIdOpt, authTokenOpt)
    BasicOrganizationView(basicOrganization, membershipInfo)
  }

  def getBasicOrganizations(orgIds: Set[Id[Organization]])(implicit session: RSession): Map[Id[Organization], BasicOrganization] = {
    val cacheFormattedMap = basicOrganizationIdCache.bulkGetOrElse(orgIds.map(BasicOrganizationIdKey)) { missing =>
      missing.map(_.id).map {
        orgId => orgId -> getBasicOrganizationHelper(orgId) // grab all the Option[BasicOrganization]
      }.collect {
        case (orgId, Some(basicOrg)) => orgId -> basicOrg // take only the active orgs (inactive ones are None)
      }.map {
        case (orgId, org) => (BasicOrganizationIdKey(orgId), org) // format them so the cache can understand them
      }.toMap
    }
    cacheFormattedMap.map { case (orgKey, org) => (orgKey.id, org) }
  }

  def getBasicOrganizationHelper(orgId: Id[Organization])(implicit session: RSession): Option[BasicOrganization] = {
    val org = orgRepo.get(orgId)
    if (org.isInactive) None
    else {
      val orgHandle = org.handle
      val orgName = org.name
      val description = org.description

      val ownerId = userRepo.get(org.ownerId).externalId
      val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).imagePath

      Some(BasicOrganization(
        orgId = Organization.publicId(orgId),
        ownerId = ownerId,
        handle = orgHandle,
        name = orgName,
        description = description,
        avatarPath = avatarPath))
    }
  }

  def getOrganizationInfos(orgIds: Set[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationInfo] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map(id => id -> getOrganizationInfo(id, viewerIdOpt)).toMap
    }
  }

  def getAccountFeatureSettings(orgId: Id[Organization]): OrganizationSettingsResponse = {
    db.readOnlyReplica { implicit session =>
      val config = orgConfigRepo.getByOrgId(orgId)
      OrganizationSettingsResponse(config)
    }
  }

  def getExternalOrgConfiguration(orgId: Id[Organization]): ExternalOrganizationConfiguration = {
    db.readOnlyReplica(implicit session => getExternalOrgConfigurationHelper(orgId))
  }

  def getExternalOrgConfigurationHelper(orgId: Id[Organization])(implicit session: RSession): ExternalOrganizationConfiguration = {
    val config = orgConfigRepo.getByOrgId(orgId)
    val plan = planManagementCommander.currentPlanHelper(orgId)
    ExternalOrganizationConfiguration(plan.showUpsells, OrganizationSettingsWithEditability(config.settings, plan.editableFeatures))
  }

  @StatsdTiming("OrganizationInfoCommander.getOrganizationInfo")
  def getOrganizationInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationInfo = {
    val viewerPermissions = permissionCommander.getOrganizationPermissions(orgId, viewerIdOpt)
    if (!viewerPermissions.contains(OrganizationPermission.VIEW_ORGANIZATION)) {
      airbrake.notify(s"Tried to serve up organization info for org $orgId to viewer $viewerIdOpt, but they do not have permission to view this org")
    }

    val org = orgRepo.get(orgId)
    if (org.state == OrganizationStates.INACTIVE) throw new Exception(s"inactive org: $org")
    val orgHandle = org.handle
    val orgName = org.name
    val description = org.description
    val site = org.site
    val ownerId = userRepo.get(org.ownerId).externalId
    val experiments = orgExperimentRepo.getOrganizationExperiments(org.id.get).toSeq

    val memberIds = {
      if (!viewerPermissions.contains(OrganizationPermission.VIEW_MEMBERS)) Seq.empty
      else orgMembershipRepo.getSortedMembershipsByOrgId(orgId, Offset(0), Limit(Int.MaxValue)).map(_.userId)
    }
    val members = userRepo.getAllUsers(memberIds).values.toSeq
    val membersAsBasicUsers = members.map(BasicUser.fromUser)
    val memberCount = members.length
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).imagePath
    val config = Some(getExternalOrgConfigurationHelper(orgId)).filter(_ => viewerPermissions.contains(OrganizationPermission.VIEW_SETTINGS))
    val numLibraries = countLibrariesVisibleToUserHelper(orgId, viewerIdOpt)
    val slackTeamOpt = Try(viewerIdOpt.flatMap(slackInfoCommander.getOrganizationSlackTeam(orgId, _))).recover {
      case fail =>
        airbrake.notify(s"Failed to generate SlackInfo for org $orgId", fail)
        None
    }.get

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
      numLibraries = numLibraries,
      config = config,
      slackTeam = slackTeamOpt,
      experiments = experiments)
  }

  private def countLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Int = {
    val viewerLibraryMemberships = userIdOpt.map(libraryMembershipRepo.getWithUserId(_).map(_.libraryId).toSet).getOrElse(Set.empty[Id[Library]])
    val includeOrgVisibleLibs = userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(orgId, _).isDefined)
    libraryRepo.countVisibleOrganizationLibraries(orgId, includeOrgVisibleLibs, viewerLibraryMemberships)
  }

  private def getMembershipInfoHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String])(implicit session: RSession): OrganizationViewerInfo = {
    val membershipOpt = viewerIdOpt.flatMap(orgMembershipRepo.getByOrgIdAndUserId(orgId, _))
    val inviteOpt = orgInviteCommander.getViewerInviteInfo(orgId, viewerIdOpt, authTokenOpt)
    val sharedEmails = viewerIdOpt.map(userId => organizationDomainOwnershipCommander.getSharedEmailsHelper(userId, orgId)).getOrElse(Set.empty)
    val permissions = permissionCommander.getOrganizationPermissions(orgId, viewerIdOpt)

    OrganizationViewerInfo(
      invite = inviteOpt,
      emails = sharedEmails,
      permissions = permissions,
      membership = membershipOpt.map(mem => OrganizationMembershipInfo(mem.role))
    )
  }

  def getOrgTrackingValues(orgId: Id[Organization]): OrgTrackingValues = {
    db.readOnlyReplica { implicit session =>
      val libraries = libraryRepo.getOrganizationLibraries(orgId)
      val libraryCount = libraries.length
      val keepCount = ktlRepo.countNonImportedKeepsInOrg(orgId)
      val inviteCount = orgInviteRepo.getCountByOrganization(orgId, decisions = Set(InvitationDecision.PENDING))
      val collabLibCount = libraryMembershipRepo.countWithAccessByLibraryId(libraries.map(_.id.get).toSet, LibraryAccess.READ_WRITE).count { case (_, memberCount) => memberCount > 0 }
      OrgTrackingValues(libraryCount, keepCount, inviteCount, collabLibCount)
    }
  }
}
