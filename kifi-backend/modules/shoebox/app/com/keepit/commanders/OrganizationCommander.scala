package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model.OrganizationModification.{ NAME_CHANGE, PERMISSIONS_CHANGE }
import com.keepit.model.OrganizationPermission.EDIT_ORGANIZATION
import com.keepit.model._

@ImplementedBy(classOf[OrganizationCommanderImpl])
trait OrganizationCommander {
  def get(orgId: Id[Organization]): Organization
}

@Singleton
class OrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    handleCommander: HandleCommander) extends OrganizationCommander with Logging {

  def get(orgId: Id[Organization]): Organization = db.readOnlyReplica { implicit session => orgRepo.get(orgId) }

  def validRequest(request: OrganizationRequest)(implicit session: RSession): Boolean = {
    request match {
      case OrganizationCreateRequest(createrId, orgName) => true // TODO: can we check ahead of time that an org handle is available?
      case OrganizationModifyRequest(orgId, requesterId, modification) =>
        orgMembershipRepo.getByOrgIdAndUserId(orgId, requesterId).map(_.permissions).exists {
          modification match {
            case NAME_CHANGE(newName) => _.contains(EDIT_ORGANIZATION)
            case PERMISSIONS_CHANGE(newBasePermissions) => _.contains(EDIT_ORGANIZATION)
          }
        }
      case OrganizationDeleteRequest(orgId, requesterId) =>
        requesterId == orgRepo.get(orgId).ownerId
    }
  }

  def createOrganization(request: OrganizationCreateRequest): Either[OrganizationFail, OrganizationCreateResponse] = {
    // TODO: if we can find a way to validate that request, maybe we don't have to try/catch
    try {
      db.readWrite { implicit session =>
        val orgPrototype = orgRepo.save(Organization(ownerId = request.userId, name = request.orgName, handle = None))
        val org = handleCommander.autoSetOrganizationHandle(orgPrototype) getOrElse {
          throw new Exception(s"COULD NOT CREATE ORGANIZATION [$request.orgName] SINCE WE DIDN'T FIND A HANDLE!!!")
        }
        Right(OrganizationCreateResponse(request, org))
      }
    } catch {
      case e: Exception => Left(OrganizationFail.HANDLE_UNAVAILABLE)
    }
  }

  def modifyOrganization(request: OrganizationModifyRequest): Either[OrganizationFail, OrganizationModifyResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val org = orgRepo.get(request.orgId)
        request.modification match {
          case NAME_CHANGE(newName) =>
            Right(OrganizationModifyResponse(request, orgRepo.save(org.withName(newName))))
          case PERMISSIONS_CHANGE(newBasePermissions) =>
            Right(OrganizationModifyResponse(request, orgRepo.save(org.withBasePermissions(newBasePermissions))))
        }
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }

  def deleteOrganization(request: OrganizationDeleteRequest): Either[OrganizationFail, OrganizationDeleteResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val org = orgRepo.get(request.orgId)
        Right(OrganizationDeleteResponse(request, orgRepo.save(org.withState(OrganizationStates.INACTIVE))))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
}
