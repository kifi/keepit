package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.abook.model.RichContact
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[OrganizationMembershipCandidateCommanderImpl])
trait OrganizationMembershipCandidateCommander {
  def addCandidates(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit]
  def removeCandidates(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit]
  def inviteCandidates(orgId: Id[Organization]): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]]
  def inviteCandidate(orgId: Id[Organization], userId: Id[User]): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]]
}

@Singleton
class OrganizationMembershipCandidateCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgInviteCommander: OrganizationInviteCommander,
    userRepo: UserRepo,
    keepRepo: KeepRepo,
    libraryRepo: LibraryRepo,
    implicit val executionContext: ExecutionContext,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends OrganizationMembershipCandidateCommander with Logging {

  def addCandidates(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit] = SafeFuture {
    db.readWrite { implicit session =>
      val existingCandidates = orgMembershipCandidateRepo.getAllByOrgId(orgId, states = OrganizationMembershipCandidateStates.all)

      val inactiveCandidates = existingCandidates.filter(_.state == OrganizationMembershipCandidateStates.INACTIVE)
      val candidatesToBeReactivated = inactiveCandidates.filter(m => userIds.contains(m.userId))
      candidatesToBeReactivated.foreach { m =>
        orgMembershipCandidateRepo.save(m.withState(OrganizationMembershipCandidateStates.ACTIVE))
      }

      val userIdsToBeAdded = userIds -- existingCandidates.map(_.userId).toSet
      userIdsToBeAdded.foreach { uid =>
        orgMembershipCandidateRepo.save(OrganizationMembershipCandidate(organizationId = orgId, userId = uid))
      }
    }
  }

  def removeCandidates(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit] = SafeFuture {
    db.readWrite { implicit session =>
      val existingCandidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).map(m => m.userId -> m).toMap
      val toBeDeactivated = userIds intersect existingCandidates.keySet
      toBeDeactivated.foreach { uid =>
        orgMembershipCandidateRepo.deactivate(existingCandidates(uid))
      }
    }
  }

  def inviteCandidates(orgId: Id[Organization]): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]] = {
    val (org, existingCandidates) = db.readOnlyReplica { implicit session =>
      (orgRepo.get(orgId), orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet)
    }

    implicit val context = heimdalContextBuilder().build
    val orgInvite = OrganizationInviteSendRequest(orgId = org.id.get, requesterId = org.ownerId, targetEmails = Set.empty, targetUserIds = existingCandidates.map(_.userId))
    orgInviteCommander.inviteToOrganization(orgInvite)
  }

  def inviteCandidate(orgId: Id[Organization], userId: Id[User]): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]] = {
    val (org, existingCandidate) = db.readOnlyReplica { implicit session =>
      (orgRepo.get(orgId), orgMembershipCandidateRepo.getByUserAndOrg(userId, orgId).get)
    }

    implicit val context = heimdalContextBuilder().build
    val orgInvite = OrganizationInviteSendRequest(orgId = org.id.get, requesterId = org.ownerId, targetEmails = Set.empty, targetUserIds = Set(existingCandidate.userId))
    orgInviteCommander.inviteToOrganization(orgInvite)
  }
}
