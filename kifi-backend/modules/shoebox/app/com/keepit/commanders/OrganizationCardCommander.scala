package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.ImageSize
import com.keepit.model._

class OrganizationCardCommander @Inject() (
    db: Database,
    organizationMembershipCommander: OrganizationMembershipCommander,
    airbrake: AirbrakeNotifier,
    orgRepo: OrganizationRepo,
    userRepo: UserRepo,
    organizationAvatarCommander: OrganizationAvatarCommander,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCommander: OrganizationMembershipCommander,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    implicit val publicIdConfiguration: PublicIdConfiguration) {

  def getOrganizationCards(orgIds: Seq[Id[Organization]], viewerIdOpt: Option[Id[User]]): Map[Id[Organization], OrganizationCard] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map { orgId => orgId -> getOrganizationCardHelper(orgId, viewerIdOpt) }.toMap
    }
  }

  def getOrganizationCardHelper(orgId: Id[Organization], viewerIdOpt: Option[Id[User]])(implicit session: RSession): OrganizationCard = {
    if (!orgMembershipCommander.getPermissionsHelper(orgId, viewerIdOpt).contains(OrganizationPermission.VIEW_ORGANIZATION)) {
      airbrake.notify(s"Tried to serve up an organization card for org $orgId to viewer $viewerIdOpt, but they do not have permission to view this org")
    }
    val org = orgRepo.get(orgId)
    val orgHandle = org.handle
    val orgName = org.name
    val description = org.description

    val ownerId = userRepo.get(org.ownerId).externalId

    val numMembers = orgMembershipRepo.countByOrgId(orgId)
    val avatarPath = organizationAvatarCommander.getBestImageByOrgId(orgId, ImageSize(200, 200)).map(_.imagePath)

    val numLibraries = countLibrariesVisibleToUserHelper(orgId, viewerIdOpt)

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

  private def countLibrariesVisibleToUserHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Int = {
    val viewerLibraryMemberships = userIdOpt.map(libraryMembershipRepo.getWithUserId(_).map(_.libraryId).toSet).getOrElse(Set.empty[Id[Library]])
    val includeOrgVisibleLibs = userIdOpt.exists(orgMembershipRepo.getByOrgIdAndUserId(orgId, _).isDefined)
    libraryRepo.countVisibleOrganizationLibraries(orgId, includeOrgVisibleLibs, viewerLibraryMemberships)
  }

}
