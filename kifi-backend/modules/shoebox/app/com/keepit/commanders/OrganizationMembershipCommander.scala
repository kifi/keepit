package com.keepit.commanders

import com.keepit.common.cache._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.json
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.net.{ NonOKResponseException, DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.social.BasicUserRepo
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilder }
import com.keepit.model.OrganizationPermission._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.typeahead.KifiUserTypeahead
import org.joda.time.DateTime
import play.api.libs.json._
import com.keepit.common.core._
import com.keepit.payments.{ PlanManagementCommander, ActionAttribution }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.util.control.NoStackTrace

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
  def getPrimaryOrganizationForUser(userId: Id[User]): Option[Id[Organization]]
  def getAllOrganizationsForUser(userId: Id[User]): Seq[Id[Organization]]
  def getAllForUsers(userIds: Set[Id[User]]): Map[Id[User], Set[OrganizationMembership]]
  def getVisibleOrganizationsForUser(userId: Id[User], viewerIdOpt: Option[Id[User]]): Seq[Id[Organization]]
  def getMemberIds(orgId: Id[Organization]): Set[Id[User]]

  def getMembership(orgId: Id[Organization], userId: Id[User]): Option[OrganizationMembership]

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse]
  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse]
  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse]

  def modifyMemberships(requests: Seq[OrganizationMembershipModifyRequest]): Map[OrganizationMembershipModifyRequest, Either[OrganizationFail, OrganizationMembershipModifyResponse]]
  def removeMemberships(requests: Seq[OrganizationMembershipRemoveRequest]): Map[OrganizationMembershipRemoveRequest, Either[OrganizationFail, OrganizationMembershipRemoveResponse]]
}

@Singleton
class OrganizationMembershipCommanderImpl @Inject() (
    db: Database,
    permissionCommander: PermissionCommander,
    primaryOrgForUserCache: PrimaryOrgForUserCache,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    organizationExperimentRepo: OrganizationExperimentRepo,
    userExperimentRepo: UserExperimentRepo,
    userRepo: UserRepo,
    elizaServiceClient: ElizaServiceClient,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipCommander: LibraryMembershipCommander,
    basicUserRepo: BasicUserRepo,
    kifiUserTypeahead: KifiUserTypeahead,
    httpClient: HttpClient,
    planCommander: PlanManagementCommander,
    implicit val executionContext: ExecutionContext) extends OrganizationMembershipCommander with Logging {

  private val httpLock = new ReactiveLock(5)

  def getPrimaryOrganizationForUser(userId: Id[User]): Option[Id[Organization]] = {
    primaryOrgForUserCache.getOrElseOpt(PrimaryOrgForUserKey(userId)) {
      db.readOnlyReplica { implicit s =>
        organizationMembershipRepo.getAllByUserId(userId).map(_.organizationId).sorted.headOption.orElse {
          organizationMembershipCandidateRepo.getAllByUserId(userId).map(_.organizationId).sorted.headOption
        }
      }
    }
  }

  def getMembership(orgId: Id[Organization], userId: Id[User]): Option[OrganizationMembership] = {
    db.readWrite { implicit session =>
      organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId)
    }
  }

  def getMembersAndUniqueInvitees(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], offset: Offset, limit: Limit, includeInvitees: Boolean): Either[OrganizationFail, Seq[MaybeOrganizationMember]] = {
    db.readOnlyMaster { implicit session =>
      if (!permissionCommander.getOrganizationPermissions(orgId, viewerIdOpt).contains(OrganizationPermission.VIEW_MEMBERS)) Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      else {
        val members = organizationMembershipRepo.getSortedMembershipsByOrgId(orgId, offset, limit)
        val invitees = includeInvitees match {
          case true =>
            if (members.length < limit.value) {
              val inviteLimit = Limit(Math.max(limit.value - members.length, 0))
              val inviteOffset = if (members.isEmpty) {
                val totalMembers = organizationMembershipRepo.countByOrgId(orgId)
                Offset(offset.value - totalMembers)
              } else {
                Offset(0)
              }
              organizationInviteRepo.getByOrganizationAndDecision(orgId, decision = InvitationDecision.PENDING, inviteOffset, inviteLimit, includeAnonymous = false)
            } else Seq.empty[OrganizationInvite]
          case false => Seq.empty[OrganizationInvite]
        }
        Right(buildMaybeMembers(members, invitees))
      }
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

  def getAllForUsers(userIds: Set[Id[User]]): Map[Id[User], Set[OrganizationMembership]] = {
    db.readOnlyReplica { implicit session =>
      organizationMembershipRepo.getAllByUserIds(userIds)
    }
  }

  def getVisibleOrganizationsForUser(userId: Id[User], viewerIdOpt: Option[Id[User]]): Seq[Id[Organization]] = {
    db.readOnlyReplica { implicit session =>
      val allOrgIds = organizationMembershipRepo.getAllByUserId(userId).map(_.organizationId)
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
    val org = organizationRepo.get(request.orgId)
    val requesterOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.requesterId)
    val requesterPermissions = permissionCommander.getOrganizationPermissions(request.orgId, Some(request.requesterId))
    val targetOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId)

    requesterOpt match {
      case None => Some(OrganizationFail.NOT_A_MEMBER)
      case Some(requester) =>
        request match {
          case OrganizationMembershipAddRequest(_, _, _, newRole) =>
            val requesterCanInviteSpecifiedRole = (newRole <= requester.role) && requesterPermissions.contains(INVITE_MEMBERS)
            if (targetOpt.isDefined) Some(OrganizationFail.ALREADY_A_MEMBER)
            else if (!requesterCanInviteSpecifiedRole) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
            else None

          case OrganizationMembershipModifyRequest(_, _, _, newRole) =>
            val requesterIsOwner = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
            val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requesterPermissions.contains(MODIFY_MEMBERS)
            if (!(requesterIsOwner || requesterOutranksTarget)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
            else None

          case OrganizationMembershipRemoveRequest(_, _, _) =>
            val requesterIsOwner = (requester.userId == org.ownerId) && !targetOpt.exists(_.userId == org.ownerId)
            val requesterRemovingSelf = targetOpt.exists(t => t.userId == requester.userId && t.userId != org.ownerId)
            val requesterOutranksTarget = targetOpt.exists(_.role < requester.role) && requesterPermissions.contains(REMOVE_MEMBERS)
            if (!(requesterIsOwner || requesterRemovingSelf || requesterOutranksTarget)) Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
            else None
        }
    }
  }

  private def unsafeAddMembership(request: OrganizationMembershipAddRequest): OrganizationMembershipAddResponse = {
    val newMembership = db.readWrite { implicit session =>
      val org = organizationRepo.get(request.orgId)
      val targetOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId, excludeState = None)
      targetOpt match {
        case Some(membership) if membership.isActive => organizationMembershipRepo.save(org.modifiedMembership(membership, request.newRole))
        case inactiveMembershipOpt =>
          val membershipIdOpt = inactiveMembershipOpt.flatMap(_.id)
          val newMembership = org.newMembership(request.targetId, request.newRole).copy(id = membershipIdOpt)
          val savedMembership = organizationMembershipRepo.save(newMembership)
          organizationMembershipCandidateRepo.getByUserAndOrg(request.targetId, request.orgId).foreach { candidate =>
            organizationMembershipCandidateRepo.save(candidate.copy(state = OrganizationMembershipCandidateStates.INACTIVE))
          }
          savedMembership
      }
    }

    // Fire off a few Futures to take care of low priority tasks
    maybeNotifySlackOfNewMember(request.orgId, request.targetId)
    refreshOrganizationMembersTypeahead(request.orgId)

    val orgGeneralLibrary = db.readOnlyReplica { implicit session => libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(request.orgId), LibraryKind.SYSTEM_ORG_GENERAL) }
    orgGeneralLibrary.foreach { lib =>
      implicit val context = HeimdalContext.empty // TODO(ryan): find someone to make this more helpful
      libraryMembershipCommander.joinLibrary(request.targetId, lib.id.get)
    }
    planCommander.registerNewUser(request.orgId, request.targetId, ActionAttribution(user = Some(request.requesterId), admin = None))
    elizaServiceClient.sendToUser(request.targetId, Json.arr("flush"))

    OrganizationMembershipAddResponse(request, newMembership)
  }

  private def modifyMembershipHelper(request: OrganizationMembershipModifyRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationMembershipModifyResponse] = {
    getValidationError(request) match {
      case Some(fail) => Left(fail)
      case None =>
        val membership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
        val org = organizationRepo.get(request.orgId)
        val newMembership = organizationMembershipRepo.save(org.modifiedMembership(membership, request.newRole))
        Right(OrganizationMembershipModifyResponse(request, newMembership))
    }
  }

  private def unsafeRemoveMembership(request: OrganizationMembershipRemoveRequest): OrganizationMembershipRemoveResponse = {
    db.readWrite { implicit session =>
      val membership = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.targetId).get
      organizationMembershipRepo.deactivate(membership)
    }
    planCommander.registerRemovedUser(request.orgId, request.targetId, ActionAttribution(user = Some(request.requesterId), admin = None))
    val orgGeneralLibrary = db.readOnlyReplica { implicit session => libraryRepo.getBySpaceAndKind(LibrarySpace.fromOrganizationId(request.orgId), LibraryKind.SYSTEM_ORG_GENERAL) }
    orgGeneralLibrary.foreach { lib =>
      implicit val context = HeimdalContext.empty // TODO(ryan): find someone to make this more helpful
      libraryMembershipCommander.leaveLibrary(lib.id.get, request.targetId)
    }
    refreshOrganizationMembersTypeahead(request.orgId)
    OrganizationMembershipRemoveResponse(request)
  }

  private def maybeNotifySlackOfNewMember(orgId: Id[Organization], userId: Id[User]): Future[Unit] = db.readOnlyReplicaAsync { implicit session =>
    val isOrgReal = !organizationExperimentRepo.hasExperiment(orgId, OrganizationExperimentType.FAKE)
    val isUserReal = !userExperimentRepo.hasExperiment(userId, UserExperimentType.FAKE)
    val shouldNotifySlack = isOrgReal && isUserReal
    if (shouldNotifySlack) {
      val org = organizationRepo.get(orgId)
      val user = userRepo.get(userId)

      val channel = "#org-members"
      val webhookUrl = "https://hooks.slack.com/services/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"

      val text = s"<http://www.kifi.com/${user.username.value}?kma=1|${user.fullName}> just joined <http://www.kifi.com/${org.handle.value}?kma=1|${org.name}>."
      val message = BasicSlackMessage(text = text, channel = Some(channel))

      val response = httpLock.withLockFuture(httpClient.postFuture(DirectUrl(webhookUrl), Json.toJson(message)))

      response.onComplete {
        case Success(res) =>
          log.info(s"[notifySlackOfNewMember] Slack message to $channel succeeded.")
        case Failure(t: NonOKResponseException) =>
          log.warn(s"[notifySlackOfNewMember] Slack info invalid for channel=$channel. Make sure the webhookUrl matches.")
        case _ =>
          log.error(s"[notifySlackOfNewMember] Slack message request failed.")
      }
    }
  }

  private def refreshOrganizationMembersTypeahead(orgId: Id[Organization]): Future[Unit] = {
    val orgMembers = getMemberIds(orgId)
    kifiUserTypeahead.refreshByIds(orgMembers.toSeq)
  }

  def addMembership(request: OrganizationMembershipAddRequest): Either[OrganizationFail, OrganizationMembershipAddResponse] = {
    val validationError = db.readOnlyReplica { implicit session => getValidationError(request) }
    validationError match {
      case Some(fail) => Left(fail)
      case None => Right(unsafeAddMembership(request))
    }
  }
  def addMemberships(requests: Seq[OrganizationMembershipAddRequest]): Map[OrganizationMembershipAddRequest, Either[OrganizationFail, OrganizationMembershipAddResponse]] = {
    requests.map { request => request -> addMembership(request) }.toMap
  }

  def modifyMembership(request: OrganizationMembershipModifyRequest): Either[OrganizationFail, OrganizationMembershipModifyResponse] =
    db.readWrite { implicit session => modifyMembershipHelper(request) }
  def modifyMemberships(requests: Seq[OrganizationMembershipModifyRequest]): Map[OrganizationMembershipModifyRequest, Either[OrganizationFail, OrganizationMembershipModifyResponse]] = {
    requests.map { request => request -> modifyMembership(request) }.toMap
  }

  def removeMembership(request: OrganizationMembershipRemoveRequest): Either[OrganizationFail, OrganizationMembershipRemoveResponse] = {
    val validationError = db.readOnlyReplica { implicit session => getValidationError(request) }
    validationError match {
      case Some(fail) => Left(fail)
      case None => Right(unsafeRemoveMembership(request))
    }
  }
  def removeMemberships(requests: Seq[OrganizationMembershipRemoveRequest]): Map[OrganizationMembershipRemoveRequest, Either[OrganizationFail, OrganizationMembershipRemoveResponse]] = {
    requests.map { request => request -> removeMembership(request) }.toMap
  }
}
