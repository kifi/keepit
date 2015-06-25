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
  def isValidRequest(request: OrganizationMembershipRequest)(implicit session: RSession): Boolean

  def validateRequests(requests: Seq[OrganizationMembershipRequest]): Map[OrganizationMembershipRequest, Boolean]

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse]
  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse]
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse]

  def addMemberships(requests: Seq[OrganizationMembershipAddRequest]): Either[OrganizationFail, Map[OrganizationMembershipAddRequest, OrganizationMembershipAddResponse]]
  def modifyMemberships(requests: Seq[OrganizationMembershipModifyRequest]): Either[OrganizationFail, Map[OrganizationMembershipModifyRequest, OrganizationMembershipModifyResponse]]
  def removeMemberships(requests: Seq[OrganizationMembershipRemoveRequest]): Either[OrganizationFail, Map[OrganizationMembershipRemoveRequest, OrganizationMembershipRemoveResponse]]
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

  def isValidRequest(request: OrganizationMembershipRequest)(implicit session: RSession): Boolean = {
    val org = organizationRepo.get(request.orgId)
    val requesterOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.requesterId)
    val targetOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId)

    requesterOpt exists { requester =>
      request match {
        case OrganizationMembershipAddRequest(_, _, _, newRole) =>
          val isInviteOrPromotion = !targetOpt.exists(_.role > newRole)
          isInviteOrPromotion && (newRole <= requester.role) &&
            requester.permissions.contains(INVITE_MEMBERS)

        case OrganizationMembershipModifyRequest(_, _, _, newRole) =>
          val requesterIsOwner = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
          val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requester.permissions.contains(MODIFY_MEMBERS)
          requesterIsOwner || requesterOutranksTarget

        case OrganizationMembershipRemoveRequest(_, _, _) =>
          val requesterRemovingSelf = targetOpt.exists(t => t.userId == requester.userId && t.userId != org.ownerId)
          val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requester.permissions.contains(REMOVE_MEMBERS)
          requesterRemovingSelf || requesterOutranksTarget
      }
    }
  }

  def validateRequests(requests: Seq[OrganizationMembershipRequest]): Map[OrganizationMembershipRequest, Boolean] = {
    db.readOnlyReplica { implicit session =>
      requests.map(r => r -> isValidRequest(r)).toMap
    }
  }

  private def addMembershipHelper(request: OrganizationMembershipAddRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    if (!isValidRequest(request)) {
      Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    } else {
      val org = organizationRepo.get(request.orgId)
      val targetOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId)
      val newMembership = targetOpt match {
        case None => organizationMembershipRepo.save(org.newMembership(request.targetId, request.newRole))
        case Some(membership) => organizationMembershipRepo.save(org.modifiedMembership(membership, request.newRole))
      }
      Right(OrganizationMembershipAddResponse(request, newMembership))
    }
  }
  private def modifyMembershipHelper(request: OrganizationMembershipModifyRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipModifyResponse] = {
    if (!isValidRequest(request)) {
      Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    } else {
      val membership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
      val org = organizationRepo.get(request.orgId)
      val newMembership = organizationMembershipRepo.save(org.modifiedMembership(membership, request.newRole))
      Right(OrganizationMembershipModifyResponse(request, newMembership))
    }
  }
  private def removeMembershipHelper(request: OrganizationMembershipRemoveRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipRemoveResponse] = {
    if (!isValidRequest(request)) {
      Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    } else {
      val membership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
      organizationMembershipRepo.deactivate(membership)
      Right(OrganizationMembershipRemoveResponse(request))
    }
  }

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse] =
    db.readWrite { implicit session => addMembershipHelper(request) }

  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse] =
    db.readWrite { implicit session => modifyMembershipHelper(request) }

  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse] =
    db.readWrite { implicit session => removeMembershipHelper(request) }

  def foldWithFail[A, B, F](f: (A => Either[F, B]), xs: Seq[A]): Map[A, B] = {
    def loop(ys: Seq[A], accum: Map[A, B]): Map[A, B] = {
      ys.headOption match {
        case None => accum
        case Some(y) =>
          f(y) match {
            case Left(fail) => throw new Exception
            case Right(z) => loop(ys.tail, accum + (y -> z))
          }
      }
    }
    loop(xs, Map[A, B]())
  }
  def addMemberships(requests: Seq[OrganizationMembershipAddRequest]): Either[OrganizationFail, Map[OrganizationMembershipAddRequest, OrganizationMembershipAddResponse]] = {
    try {
      val responses = db.readWrite { implicit session => foldWithFail(addMembershipHelper, requests) }
      Right(responses)
    } catch {
      case _: Exception => Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    }
  }

  def modifyMemberships(requests: Seq[OrganizationMembershipModifyRequest]): Either[OrganizationFail, Map[OrganizationMembershipModifyRequest, OrganizationMembershipModifyResponse]] = {
    try {
      val responses = db.readWrite { implicit session => foldWithFail(modifyMembershipHelper, requests) }
      Right(responses)
    } catch {
      case _: Exception => Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    }
  }

  def removeMemberships(requests: Seq[OrganizationMembershipRemoveRequest]): Either[OrganizationFail, Map[OrganizationMembershipRemoveRequest, OrganizationMembershipRemoveResponse]] = {
    try {
      val responses = db.readWrite { implicit session => foldWithFail(removeMembershipHelper, requests) }
      Right(responses)
    } catch {
      case _: Exception => Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    }
  }
}
