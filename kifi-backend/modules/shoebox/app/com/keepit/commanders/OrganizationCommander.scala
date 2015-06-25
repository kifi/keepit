package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model.OrganizationPermission.{ VIEW_ORGANIZATION, EDIT_ORGANIZATION }
import com.keepit.model._

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def get(orgId: Id[Organization]): Organization
  def isValidRequest(request: OrganizationRequest)(implicit session: RSession): Boolean
  def createOrganization(request: OrganizationCreateRequest): Either[OrganizationFail, OrganizationCreateResponse]
  def modifyOrganization(request: OrganizationModifyRequest): Either[OrganizationFail, OrganizationModifyResponse]
  def deleteOrganization(request: OrganizationDeleteRequest): Either[OrganizationFail, OrganizationDeleteResponse]
}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgInviteRepo: OrganizationInviteRepo,
    handleCommander: HandleCommander) extends OrganizationCommander with Logging {

  def get(orgId: Id[Organization]): Organization = db.readOnlyReplica { implicit session => orgRepo.get(orgId) }

  def isValidRequest(request: OrganizationRequest)(implicit session: RSession): Boolean = {
    request match {
      case OrganizationCreateRequest(createrId, orgName) => true // TODO: can we check ahead of time that an org handle is available?
      case OrganizationModifyRequest(orgId, requesterId, modifications) =>
        val permissions = orgMembershipRepo.getByOrgIdAndUserId(orgId, requesterId).map(_.permissions)
        permissions.exists { _.contains(EDIT_ORGANIZATION) } && areAllValidModifications(modifications)
      case OrganizationDeleteRequest(orgId, requesterId) =>
        requesterId == orgRepo.get(orgId).ownerId
    }
  }
  private def areAllValidModifications(modifications: OrganizationModifications): Boolean = {
    lazy val badName = modifications.newName.exists(_.isEmpty)
    lazy val badBasePermissions = modifications.newBasePermissions.exists { bps =>
      // Are there any members that can't even see the organization?
      OrganizationRole.all exists { role => !bps.forRole(role).contains(VIEW_ORGANIZATION) }
    }
    !badName && !badBasePermissions
  }

  def createOrganization(request: OrganizationCreateRequest): Either[OrganizationFail, OrganizationCreateResponse] = {
    // TODO: if we can find a way to validate that request, maybe we don't have to try/catch
    try {
      db.readWrite { implicit session =>
        if (isValidRequest(request)) {
          val orgPrototype = orgRepo.save(Organization(ownerId = request.userId, name = request.orgName, handle = None))
          val org = handleCommander.autoSetOrganizationHandle(orgPrototype) getOrElse {
            throw new Exception(s"COULD NOT CREATE ORGANIZATION [$request.orgName] SINCE WE DIDN'T FIND A HANDLE!!!")
          }
          orgMembershipRepo.save(org.newMembership(userId = request.userId, role = OrganizationRole.OWNER))
          Right(OrganizationCreateResponse(request, org))
        } else {
          Left(OrganizationFail.HANDLE_UNAVAILABLE)
        }
      }
    } catch {
      case e: Exception => Left(OrganizationFail.HANDLE_UNAVAILABLE)
    }
  }

  def modifyOrganization(request: OrganizationModifyRequest): Either[OrganizationFail, OrganizationModifyResponse] = {
    db.readWrite { implicit session =>
      if (isValidRequest(request)) {
        val org = orgRepo.get(request.orgId)

        if (request.modifications.newBasePermissions.nonEmpty) {
          val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
          applyNewBasePermissionsToMembers(memberships, org.basePermissions, request.modifications.newBasePermissions.get)
        }

        val modifiedOrg = org.withName(request.modifications.newName.getOrElse(org.name))
          .withBasePermissions(request.modifications.newBasePermissions.getOrElse(org.basePermissions))

        Right(OrganizationModifyResponse(request, orgRepo.save(modifiedOrg)))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
  private def applyNewBasePermissionsToMembers(memberships: Seq[OrganizationMembership], oldBasePermissions: BasePermissions, newBasePermissions: BasePermissions)(implicit session: RWSession): Unit = {
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
      if (isValidRequest(request)) {
        val org = orgRepo.get(request.orgId)

        val memberships = orgMembershipRepo.getAllByOrgId(org.id.get)
        memberships.foreach { membership => orgMembershipRepo.deactivate(membership) }

        val invites = orgInviteRepo.getAllByOrganization(org.id.get)
        invites.foreach { invite => orgInviteRepo.deactivate(invite.id.get) }

        orgRepo.save(org.sanitizeForDelete)
        handleCommander.reclaimAll(org.id.get, overrideProtection = true, overrideLock = true)
        Right(OrganizationDeleteResponse(request))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
}
