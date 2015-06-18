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
    // TODO: Clean this up. This gross logic can be simplified
    val org = organizationRepo.get(orgId)
    userIdOpt match {
      case None => org.nonmemberPermissions
      case Some(userId) =>
        val memberPermissionsOpt = organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId).map(_.permissions)
        memberPermissionsOpt match {
          case Some(permissions) => permissions
          case None =>
            val inviteRoleOpt = organizationInviteRepo.getByOrgIdAndUserId(orgId, userId).map(_.role).maxOpt
            inviteRoleOpt match {
              case Some(role) => org.rolePermissions(role)
              case None => org.nonmemberPermissions
            }
        }
    }
  }

  def getMembersAndInvitees(orgId: Id[Organization], limit: Limit, offset: Offset, includeInvitees: Boolean): Seq[MaybeOrganizationMember] = {
    db.readOnlyMaster { implicit session =>
      val members = organizationMembershipRepo.getbyOrgId(orgId, limit, offset)
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

  private def validRequest(request: OrganizationMembershipRequest)(implicit session: RSession): Boolean = {
    val requesterOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.requesterId)
    val targetOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId)

    requesterOpt exists { requester =>
      request match {
        case OrganizationMembershipAddRequest(_, _, _, newRole) =>
          targetOpt.isEmpty && (newRole <= requester.role)

        case OrganizationMembershipModifyRequest(_, _, _, newRole) =>
          targetOpt.exists(_.role < requester.role) && (newRole <= requester.role)

        case OrganizationMembershipRemoveRequest(_, _, _) =>
          targetOpt.exists(_.role < requester.role)
      }
    }
  }

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val org = organizationRepo.get(request.orgId)
        organizationMembershipRepo.save(org.newMembership(request.targetId, request.newRole))
        Right(OrganizationMembershipAddResponse(request))
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
        organizationMembershipRepo.save(org.modifiedMembership(membership, request.newRole))
        Right(OrganizationMembershipModifyResponse(request))
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
