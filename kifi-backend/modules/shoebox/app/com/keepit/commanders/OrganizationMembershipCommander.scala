package com.keepit.commanders

import com.keepit.common.core._
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import com.keepit.common.json
import play.api.libs.json._
import com.keepit.model.OrganizationPermission._

final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], role: Option[OrganizationRole], lastInvitedAt: Option[DateTime])

object MaybeOrganizationMember {
  implicit val writes = Writes[MaybeOrganizationMember] { member =>
    val identityFields = member.member.fold(user => Json.toJson(user), contact => Json.toJson(contact)).as[JsObject]
    val relatedFields = Json.obj("role" -> member.role, "lastInvitedAt" -> member.lastInvitedAt)
    json.minify(identityFields ++ relatedFields)
  }
}

@ImplementedBy(classOf[OrganizationMembershipCommanderImpl])
trait OrganizationMembershipCommander {
  def getMembersAndInvitees(orgId: Id[Organization], limit: Limit, offset: Offset, includeInvitees: Boolean): Seq[MaybeOrganizationMember]

  def getPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]]): Set[OrganizationPermission]
  def validRequest(request: OrganizationMembershipRequest)(implicit session: RSession): Boolean

  def validateRequests(requests: Seq[OrganizationMembershipRequest]): Map[OrganizationMembershipRequest, Boolean]
  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse]
  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse]
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse]
}

@Singleton
class OrganizationMembershipCommanderImpl @Inject() (
    db: Database,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo) extends OrganizationMembershipCommander with Logging {

  def getPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]]): Set[OrganizationPermission] = db.readOnlyReplica { implicit session =>
    val org = organizationRepo.get(orgId)
    userIdOpt match {
      case None => org.getNonmemberPermissions
      case Some(userId) =>
        organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId).map(_.permissions) getOrElse {
          val invites = organizationInviteRepo.getByOrgIdAndUserId(orgId, userId)
          if (invites.isEmpty) org.getNonmemberPermissions
          else org.getNonmemberPermissions + VIEW_ORGANIZATION
        }
    }
  }

  def getMembersAndInvitees(orgId: Id[Organization], limit: Limit, offset: Offset, includeInvitees: Boolean): Seq[MaybeOrganizationMember] = {
    db.readOnlyMaster { implicit session =>
      val members = organizationMembershipRepo.getByOrgId(orgId, limit, offset)
      val invitees = includeInvitees match {
        case true =>
          val leftOverCount = Limit(Math.max(limit.value - members.length, 0))
          val leftOverOffset = if (members.isEmpty) {
            val totalCountForOrg = organizationMembershipRepo.countByOrgId(orgId)
            Offset(offset.value - totalCountForOrg)
          } else {
            Offset(0)
          }
          organizationInviteRepo.getByOrganization(orgId, leftOverCount, leftOverOffset)
        case false => Seq.empty[OrganizationInvite]
      }
      buildMaybeMembers(members, invitees)
    }
  }

  private def buildMaybeMembers(members: Seq[OrganizationMembership], invitees: Seq[OrganizationInvite]): Seq[MaybeOrganizationMember] = {
    val (invitedUserIds, invitedEmailAddresses) = invitees.partition(_.userId.nonEmpty)
    val usersMap = db.readOnlyMaster { implicit session =>
      basicUserRepo.loadAllActive((members.map(_.userId) ++ invitedUserIds.map(_.userId.get)).toSet)
    }

    val membersNotIncludingOwner = members.filterNot(_.isOwner).flatMap { member =>
      usersMap.get(member.userId) map { basicUser =>
        MaybeOrganizationMember(member = Left(basicUser), role = Some(member.role), lastInvitedAt = Some(member.updatedAt))
      }
    }

    val invitedByUserId = invitedUserIds flatMap { invitedById =>
      usersMap.get(invitedById.userId.get) map { basicUser =>
        MaybeOrganizationMember(member = Left(basicUser), role = Some(invitedById.role), lastInvitedAt = Some(invitedById.updatedAt))
      }
    }

    val invitedByEmailAddress = invitedEmailAddresses map { invitedByAddress =>
      val contact = BasicContact(invitedByAddress.emailAddress.get)
      MaybeOrganizationMember(member = Right(contact), role = Some(invitedByAddress.role), lastInvitedAt = Some(invitedByAddress.updatedAt))
    }

    membersNotIncludingOwner ++ invitedByUserId ++ invitedByEmailAddress
  }

  def validRequest(request: OrganizationMembershipRequest)(implicit session: RSession): Boolean = {
    val org = organizationRepo.get(request.orgId)
    val requesterOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.requesterId)
    val targetOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId)

    requesterOpt exists { requester =>
      request match {
        case OrganizationMembershipAddRequest(_, _, _, newRole) =>
          targetOpt.isEmpty && (newRole <= requester.role) &&
            requester.permissions.contains(INVITE_MEMBERS)

        case OrganizationMembershipModifyRequest(_, _, _, newRole) =>
          val ownerModify = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
          val otherModify = targetOpt.exists(_.role < requester.role) && requester.permissions.contains(MODIFY_MEMBERS)
          ownerModify || otherModify

        case OrganizationMembershipRemoveRequest(_, _, _) =>
          val selfRemove = targetOpt.exists(t => t.userId == requester.userId && t.userId != org.ownerId)
          val otherRemove = targetOpt.exists(_.role < requester.role) && requester.permissions.contains(REMOVE_MEMBERS)
          selfRemove || otherRemove
      }
    }
  }

  def validateRequests(requests: Seq[OrganizationMembershipRequest]): Map[OrganizationMembershipRequest, Boolean] = {
    db.readOnlyReplica { implicit session =>
      requests.map(r => r -> validRequest(r)).toMap
    }
  }

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val org = organizationRepo.get(request.orgId)
        val newMembership = organizationMembershipRepo.save(org.newMembership(request.targetId, request.newRole))
        Right(OrganizationMembershipAddResponse(request, newMembership))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }

  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val membership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
        val org = organizationRepo.get(request.orgId)
        val newMembership = organizationMembershipRepo.save(org.modifiedMembership(membership, request.newRole))
        Right(OrganizationMembershipModifyResponse(request, newMembership))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val membership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
        organizationMembershipRepo.deactivate(membership.id.get)
        Right(OrganizationMembershipRemoveResponse(request))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
}
