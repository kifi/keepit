package com.keepit.commanders.gen

import com.google.inject.Inject
import com.keepit.commanders.OrganizationAvatarCommander
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.store.ImageSize
import com.keepit.model._

class BasicOrganizationGen @Inject() (
    orgRepo: OrganizationRepo,
    userRepo: UserRepo,
    basicOrganizationIdCache: BasicOrganizationIdCache,
    organizationAvatarCommander: OrganizationAvatarCommander,
    implicit val publicIdConfig: PublicIdConfiguration) {

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
}
