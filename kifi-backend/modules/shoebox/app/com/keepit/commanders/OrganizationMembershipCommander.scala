package com.keepit.commanders

import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.json
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.social.BasicUserRepo
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ HeimdalContext }
import com.keepit.model.OrganizationPermission._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.typeahead.KifiUserTypeahead
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.payments.{ RewardTrigger, CreditRewardCommander, PlanManagementCommander, ActionAttribution }

import scala.concurrent.{ ExecutionContext, Future }

final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], role: OrganizationRole, lastInvitedAt: Option[DateTime])

object MaybeOrganizationMember {
  implicit val writes = Writes[MaybeOrganizationMember] { member =>
    val identityFields = member.member.fold(user => Json.toJson(user), contact => Json.toJson(contact)).as[JsObject]
    val relatedFields = Json.obj("role" -> member.role, "lastInvitedAt" -> member.lastInvitedAt)
    json.aggressiveMinify(identityFields ++ relatedFields)
  }
}

@ImplementedBy(classOf[OrganizationMembershipCommanderImpl])
trait OrganizationMembershipCommander {
  def getMembersAndUniqueInvitees(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], offset: Offset, limit: Limit, includeInvitees: Boolean): Either[OrganizationFail, Seq[MaybeOrganizationMember]]
  def getOrganizationsForUser(userId: Id[User], limit: Limit, offset: Offset): Seq[Id[Organization]]
  def getFirstOrganizationForUser(userId: Id[User]): Option[Id[Organization]]
  def getAllOrganizationsForUser(userId: Id[User]): Seq[Id[Organization]]
  def getAllForUsers(userIds: Set[Id[User]]): Map[Id[User], Set[OrganizationMembership]]
  def getVisibleOrganizationsForUser(userId: Id[User], viewerIdOpt: Option[Id[User]]): Seq[Id[Organization]]
  def getMemberIds(orgId: Id[Organization]): Set[Id[User]]

  def getMembership(orgId: Id[Organization], userId: Id[User]): Option[OrganizationMembership]

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse]
  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse]
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse]

  def addMembershipHelper(request: OrganizationMembershipAddRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipAddResponse]
  def modifyMembershipHelper(request: OrganizationMembershipModifyRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipModifyResponse]

  def unsafeAddMembership(request: OrganizationMembershipAddRequest, isAdmin: Boolean = false)(implicit session: RWSession): OrganizationMembershipAddResponse
  def unsafeRemoveMembership(request: OrganizationMembershipRemoveRequest, isAdmin: Boolean = false): OrganizationMembershipRemoveResponse
  def unsafeModifyMembership(request: OrganizationMembershipModifyRequest, isAdmin: Boolean = false)(implicit session: RWSession): OrganizationMembershipModifyResponse
}

@Singleton
class OrganizationMembershipCommanderImpl @Inject() (
    db: Database,
    permissionCommander: PermissionCommander,
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgInviteRepo: OrganizationInviteRepo,
    elizaServiceClient: ElizaServiceClient,
    libraryRepo: LibraryRepo,
    libraryMembershipCommander: LibraryMembershipCommander,
    orgDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    basicUserRepo: BasicUserRepo,
    kifiUserTypeahead: KifiUserTypeahead,
    planCommander: PlanManagementCommander,
    creditRewardCommander: CreditRewardCommander,
    implicit val executionContext: ExecutionContext) extends OrganizationMembershipCommander with Logging {

  def getFirstOrganizationForUser(userId: Id[User]): Option[Id[Organization]] = {
    primaryOrgForUserCache.getOrElseOpt(PrimaryOrgForUserKey(userId)) {
      db.readOnlyReplica { implicit s =>
        orgMembershipRepo.getAllByUserId(userId).map(_.organizationId).sorted.headOption
      }
    }
  }

  def getMembership(orgId: Id[Organization], userId: Id[User]): Option[OrganizationMembership] = {
    db.readWrite { implicit session =>
      orgMembershipRepo.getByOrgIdAndUserId(orgId, userId)
    }
  }

  def getMembersAndUniqueInvitees(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], offset: Offset, limit: Limit, includeInvitees: Boolean): Either[OrganizationFail, Seq[MaybeOrganizationMember]] = {
    db.readOnlyMaster { implicit session =>
      if (!permissionCommander.getOrganizationPermissions(orgId, viewerIdOpt).contains(OrganizationPermission.VIEW_MEMBERS)) Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      else {
        val members = orgMembershipRepo.getSortedMembershipsByOrgId(orgId, offset, limit)
        val invitees = includeInvitees match {
          case true =>
            if (members.length < limit.value) {
              val inviteLimit = Limit(Math.max(limit.value - members.length, 0))
              val inviteOffset = if (members.isEmpty) {
                val totalMembers = orgMembershipRepo.countByOrgId(orgId)
                Offset(offset.value - totalMembers)
              } else {
                Offset(0)
              }
              orgInviteRepo.getByOrganizationAndDecision(orgId, decision = InvitationDecision.PENDING, inviteOffset, inviteLimit, includeAnonymous = false)
            } else Seq.empty[OrganizationInvite]
          case false => Seq.empty[OrganizationInvite]
        }
        Right(buildMaybeMembers(members, invitees))
      }
    }
  }

  def getMemberIds(orgId: Id[Organization]): Set[Id[User]] = {
    db.readOnlyReplica { implicit session =>
      orgMembershipRepo.getAllByOrgId(orgId).map(_.userId)
    }
  }

  def getOrganizationsForUser(userId: Id[User], limit: Limit, offset: Offset): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      orgMembershipRepo.getByUserId(userId, limit, offset).map(_.organizationId)
    }
  }

  def getAllOrganizationsForUser(userId: Id[User]): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      orgMembershipRepo.getAllByUserId(userId).map(_.organizationId)
    }
  }

  def getAllForUsers(userIds: Set[Id[User]]): Map[Id[User], Set[OrganizationMembership]] = {
    db.readOnlyReplica { implicit session =>
      orgMembershipRepo.getAllByUserIds(userIds)
    }
  }

  def getVisibleOrganizationsForUser(userId: Id[User], viewerIdOpt: Option[Id[User]]): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      val allOrgIds = orgMembershipRepo.getAllByUserId(userId).map(_.organizationId)
      allOrgIds.filter(permissionCommander.getOrganizationPermissions(_, viewerIdOpt).contains(OrganizationPermission.VIEW_ORGANIZATION))
    }
  }

  private def buildMaybeMembers(members: Seq[OrganizationMembership], invitees: Seq[OrganizationInvite]): Seq[MaybeOrganizationMember] = {
    val invitedUserIds = invitees.filter(_.userId.isDefined)
    val invitedEmailAddresses = invitees.filter(invite => invite.userId.isEmpty && invite.emailAddress.isDefined)

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
    getValidationError(request).isEmpty
  }

  private def getValidationError(request: OrganizationMembershipRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val org = orgRepo.get(request.orgId)
    val requesterOpt = orgMembershipRepo.getByOrgIdAndUserId(request.orgId, request.requesterId)
    val requesterPermissions = permissionCommander.getOrganizationPermissions(request.orgId, Some(request.requesterId))
    val targetOpt = orgMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId)

    (requesterOpt, request) match {
      case (_, _: OrganizationMembershipAddRequest) =>
        if (targetOpt.isDefined) Some(OrganizationFail.ALREADY_A_MEMBER)
        else None

      case (Some(requester), OrganizationMembershipModifyRequest(_, _, _, newRole)) =>
        val requesterIsOwner = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
        val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requesterPermissions.contains(MODIFY_MEMBERS)
        val isNoOp = targetOpt.exists(_.role == newRole)
        if (!(requesterIsOwner || requesterOutranksTarget)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None

      case (Some(requester), OrganizationMembershipRemoveRequest(_, _, _)) =>
        val requesterIsOwner = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
        val requesterRemovingSelf = targetOpt.exists(t => t.userId == requester.userId && t.userId != org.ownerId)
        val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requesterPermissions.contains(REMOVE_MEMBERS)
        if (!(requesterIsOwner || requesterRemovingSelf || requesterOutranksTarget)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        else None
      case (None, _) => Some(OrganizationFail.NOT_A_MEMBER)
    }
  }

  def addMembershipHelper(request: OrganizationMembershipAddRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    getValidationError(request) match {
      case Some(fail) => Left(fail)
      case None => Right(unsafeAddMembership(request))
    }
  }

  def unsafeAddMembership(request: OrganizationMembershipAddRequest, isAdmin: Boolean = false)(implicit session: RWSession): OrganizationMembershipAddResponse = {
    val newMembership = {
      orgMembershipCandidateRepo.getByUserAndOrg(request.targetId, request.orgId).foreach(orgMembershipCandidateRepo.deactivate)
      val inactiveMembershipOpt = orgMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId, excludeState = None)
      assert(inactiveMembershipOpt.forall(_.isInactive))
      val membership = OrganizationMembership(organizationId = request.orgId, userId = request.targetId, role = request.newRole)
      planCommander.registerNewUser(request.orgId, request.targetId, request.newRole, ActionAttribution(request.requesterId, isAdmin))
      orgMembershipRepo.save(membership.copy(id = inactiveMembershipOpt.map(_.id.get)))
    }
    creditRewardCommander.registerRewardTrigger(RewardTrigger.OrganizationMemberAdded(request.orgId, orgMembershipRepo.countByOrgId(request.orgId)))

    // Fire off a few Futures to take care of low priority tasks
    val orgGeneralLibraryId = libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(request.orgId), LibraryKind.SYSTEM_ORG_GENERAL).map(_.id.get)
    session.onTransactionSuccess {
      refreshOrganizationMembersTypeahead(request.orgId)
      orgGeneralLibraryId.foreach { libId =>
        libraryMembershipCommander.joinLibrary(request.targetId, libId)(HeimdalContext.empty)
      }
      elizaServiceClient.flush(request.targetId)
    }

    OrganizationMembershipAddResponse(request, newMembership)
  }

  def modifyMembershipHelper(request: OrganizationMembershipModifyRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipModifyResponse] = {
    getValidationError(request) match {
      case Some(fail) => Left(fail)
      case None => Right(unsafeModifyMembership(request))
    }
  }

  def unsafeModifyMembership(request: OrganizationMembershipModifyRequest, isAdmin: Boolean = false)(implicit session: RWSession): OrganizationMembershipModifyResponse = {
    val membership = orgMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
    if (membership.role == request.newRole) OrganizationMembershipModifyResponse(request, membership)
    else {
      val org = orgRepo.get(request.orgId)
      val newMembership = orgMembershipRepo.save(membership.withRole(request.newRole))
      planCommander.registerRoleChanged(org.id.get, request.targetId, from = membership.role, to = request.newRole, ActionAttribution(request.requesterId, isAdmin))
      OrganizationMembershipModifyResponse(request, newMembership)
    }
  }

  def unsafeRemoveMembership(request: OrganizationMembershipRemoveRequest, isAdmin: Boolean = false): OrganizationMembershipRemoveResponse = {
    db.readWrite { implicit session =>
      val membership = orgMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
      planCommander.registerRemovedUser(request.orgId, request.targetId, membership.role, ActionAttribution(request.requesterId, isAdmin))
      orgMembershipRepo.deactivate(membership)
    }
    orgDomainOwnershipCommander.hideOrganizationForUser(request.targetId, request.orgId)
    val orgGeneralLibrary = db.readOnlyReplica { implicit session => libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(request.orgId), LibraryKind.SYSTEM_ORG_GENERAL) }
    orgGeneralLibrary.foreach { lib =>
      libraryMembershipCommander.leaveLibrary(lib.id.get, request.targetId)(HeimdalContext.empty)
    }
    refreshOrganizationMembersTypeahead(request.orgId)
    OrganizationMembershipRemoveResponse(request)
  }

  private def refreshOrganizationMembersTypeahead(orgId: Id[Organization]): Future[Unit] = {
    val orgMembers = getMemberIds(orgId)
    kifiUserTypeahead.refreshByIds(orgMembers.toSeq)
  }

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    db.readWrite { implicit session => addMembershipHelper(request) }
  }

  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse] =
    db.readWrite { implicit session => modifyMembershipHelper(request) }

  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse] = {
    val validationError = db.readOnlyReplica { implicit session => getValidationError(request) }
    validationError match {
      case Some(fail) => Left(fail)
      case None => Right(unsafeRemoveMembership(request))
    }
  }
}
