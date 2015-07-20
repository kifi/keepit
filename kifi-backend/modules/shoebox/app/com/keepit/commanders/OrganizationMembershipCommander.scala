package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.json
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.social.BasicUserRepo
import com.keepit.model.OrganizationPermission._
import com.keepit.model._
import com.keepit.social.BasicUser
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.control.NoStackTrace

final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], role: OrganizationRole, lastInvitedAt: Option[DateTime])

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
  def getOrganizationsForUser(userId: Id[User], limit: Limit, offset: Offset): Seq[Id[Organization]]
  def getAllOrganizationsForUser(userId: Id[User]): Seq[Id[Organization]]
  def getVisibleOrganizationsForUser(userId: Id[User], viewerIdOpt: Option[Id[User]]): Seq[Id[Organization]]
  def getMemberIds(orgId: Id[Organization]): Set[Id[User]]

  def getMembership(orgId: Id[Organization], userId: Id[User]): Option[OrganizationMembership]
  def getPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]]): Set[OrganizationPermission]
  def getPermissionsHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission]
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
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    basicUserRepo: BasicUserRepo) extends OrganizationMembershipCommander with Logging {

  def getMembership(orgId: Id[Organization], userId: Id[User]): Option[OrganizationMembership] = {
    db.readWrite { implicit session =>
      organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId)
    }
  }

  def getPermissions(orgId: Id[Organization], userIdOpt: Option[Id[User]]): Set[OrganizationPermission] = {
    db.readOnlyReplica { implicit session => getPermissionsHelper(orgId, userIdOpt) }
  }
  def getPermissionsHelper(orgId: Id[Organization], userIdOpt: Option[Id[User]])(implicit session: RSession): Set[OrganizationPermission] = {
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

  def getMemberIds(orgId: Id[Organization]): Set[Id[User]] = {
    db.readOnlyReplica { implicit session =>
      organizationMembershipRepo.getAllByOrgId(orgId).map(_.userId)
    }
  }

  def getOrganizationsForUser(userId: Id[User], limit: Limit, offset: Offset): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      organizationMembershipRepo.getByUserId(userId, limit, offset).map(_.organizationId)
    }
  }
  def getAllOrganizationsForUser(userId: Id[User]): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      organizationMembershipRepo.getAllByUserId(userId).map(_.organizationId)
    }
  }

  def getVisibleOrganizationsForUser(userId: Id[User], viewerIdOpt: Option[Id[User]]): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      val allOrgIds = organizationMembershipRepo.getAllByUserId(userId).map(_.organizationId)
      allOrgIds.filter(getPermissionsHelper(_, viewerIdOpt).contains(OrganizationPermission.VIEW_ORGANIZATION))
    }
  }

  private def buildMaybeMembers(members: Seq[OrganizationMembership], invitees: Seq[OrganizationInvite]): Seq[MaybeOrganizationMember] = {
    val invitedUserIds = invitees.filter(_.userId.isDefined)
    val invitedEmailAddresses = invitees.filter(_.emailAddress.isDefined)
    val usersMap = db.readOnlyMaster { implicit session =>
      basicUserRepo.loadAllActive((members.map(_.userId) ++ invitedUserIds.map(_.userId.get)).toSet)
    }

    val membersInfo = members.flatMap { member =>
      usersMap.get(member.userId) map { basicUser =>
        MaybeOrganizationMember(member = Left(basicUser), role = member.role, lastInvitedAt = None)
      }
    }

    val invitedByUserIdInfo = invitedUserIds flatMap { invitedById =>
      usersMap.get(invitedById.userId.get) map { basicUser =>
        MaybeOrganizationMember(member = Left(basicUser), role = invitedById.role, lastInvitedAt = Some(invitedById.updatedAt))
      }
    }

    val invitedByEmailAddressInfo = invitedEmailAddresses map { invitedByAddress =>
      val contact = BasicContact(invitedByAddress.emailAddress.get)
      MaybeOrganizationMember(member = Right(contact), role = invitedByAddress.role, lastInvitedAt = Some(invitedByAddress.updatedAt))
    }

    membersInfo ++ invitedByUserIdInfo ++ invitedByEmailAddressInfo
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
          val requesterIsOwner = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
          val requesterRemovingSelf = targetOpt.exists(t => t.userId == requester.userId && t.userId != org.ownerId)
          val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requester.permissions.contains(REMOVE_MEMBERS)
          requesterIsOwner || requesterRemovingSelf || requesterOutranksTarget
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

  // Accumulates the A -> B results in a Map. If any of the results fail, throw an exception
  case class OrganizationFailException(failure: OrganizationFail) extends Exception with NoStackTrace
  def accumulateWithFail[A, B](f: (A => Either[OrganizationFail, B]), xs: Seq[A]): Map[A, B] = {
    def g(accum: Map[A, B], x: A) = f(x) match {
      case Left(failure) => throw OrganizationFailException(failure)
      case Right(y) => accum + (x -> y)
    }
    xs.foldLeft(Map.empty[A, B])(g)
  }

  def addMemberships(requests: Seq[OrganizationMembershipAddRequest]): Either[OrganizationFail, Map[OrganizationMembershipAddRequest, OrganizationMembershipAddResponse]] = {
    try {
      db.readWrite { implicit session => Right(accumulateWithFail(addMembershipHelper, requests)) }
    } catch {
      case OrganizationFailException(failure) => Left(failure)
    }
  }

  def modifyMemberships(requests: Seq[OrganizationMembershipModifyRequest]): Either[OrganizationFail, Map[OrganizationMembershipModifyRequest, OrganizationMembershipModifyResponse]] = {
    try {
      db.readWrite { implicit session => Right(accumulateWithFail(modifyMembershipHelper, requests)) }
    } catch {
      case OrganizationFailException(failure) => Left(failure)
    }
  }

  def removeMemberships(requests: Seq[OrganizationMembershipRemoveRequest]): Either[OrganizationFail, Map[OrganizationMembershipRemoveRequest, OrganizationMembershipRemoveResponse]] = {
    try {
      db.readWrite { implicit session => Right(accumulateWithFail(removeMembershipHelper, requests)) }
    } catch {
      case OrganizationFailException(failure) => Left(failure)
    }
  }
}
