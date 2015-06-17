package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
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

  def getMemberPermissions(orgId: Id[Organization], userId: Id[User]): Option[Set[OrganizationPermission]]

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse]
  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse]
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse]
}

@Singleton
class OrganizationMembershipCommanderImpl @Inject() (
    db: Database,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo) extends OrganizationMembershipCommander with Logging {

  def getMemberPermissions(orgId: Id[Organization], userId: Id[User]): Option[Set[OrganizationPermission]] = {
    val membershipOpt = db.readOnlyReplica { implicit session =>
      organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId)
    }
    membershipOpt.map { _.permissions }
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

    if (requesterOpt.isEmpty) return false // a non-member can't request anything
    val requester = requesterOpt.get

    request match {
      case OrganizationMembershipAddRequest(_, _, _, newRole) => validAdd(requester, targetOpt, newRole)
      case OrganizationMembershipModifyRequest(_, _, _, newRole) => validModify(requester, targetOpt, newRole)
      case OrganizationMembershipRemoveRequest(_, _, _) => validRemove(requester, targetOpt)
    }
  }
  private def validAdd(requester: OrganizationMembership, targetOpt: Option[OrganizationMembership], newRole: OrganizationRole): Boolean = {
    targetOpt match {
      case None => newRole <= requester.role
      case Some(_) => false
    }
  }
  private def validModify(requester: OrganizationMembership, targetOpt: Option[OrganizationMembership], newRole: OrganizationRole): Boolean = {
    targetOpt match {
      case None => false
      case Some(target) => target.role <= requester.role && newRole <= requester.role
    }
  }
  private def validRemove(requester: OrganizationMembership, targetOpt: Option[OrganizationMembership]): Boolean = {
    targetOpt match {
      case None => false
      case Some(target) => target.role <= requester.role
    }
  }

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        organizationMembershipRepo.save(OrganizationMembership(organizationId = request.orgId, userId = request.targetId, role = request.newRole))
        Right(OrganizationMembershipAddResponse(request))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }

  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val oldMembership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
        organizationMembershipRepo.save(oldMembership.withRole(request.newRole))
        Right(OrganizationMembershipModifyResponse(request))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse] = {
    db.readWrite { implicit session =>
      if (validRequest(request)) {
        val oldMembership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
        organizationMembershipRepo.deactivate(oldMembership.id.get)
        Right(OrganizationMembershipRemoveResponse(request))
      } else {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      }
    }
  }
}
