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
  def inviteCandidates(orgId: Id[Organization]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]]
  def getCandidatesInfo(orgId: Id[Organization]): Map[Id[User], OrganizationMembershipInfo]
  def getCandidatesInfos(orgIds: Seq[Id[Organization]]): Map[Id[Organization], Map[Id[User], OrganizationMembershipInfo]]
}

@Singleton
class OrganizationMembershipCandidateCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    orgMembershipCandidateRepo: OrganizationMembershipCandidateRepo,
    orgInviteCommander: OrganizationInviteCommander,
    keepRepo: KeepRepo,
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
        orgMembershipCandidateRepo.save(OrganizationMembershipCandidate(orgId = orgId, userId = uid))
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

  def inviteCandidates(orgId: Id[Organization]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]] = {
    val (org, existingCandidates) = db.readOnlyReplica { implicit session =>
      (orgRepo.get(orgId), orgMembershipCandidateRepo.getAllByOrgId(orgId).toSet)
    }

    val inviteRequests = existingCandidates.map { m =>
      OrganizationMemberInvitation(invited = Left(m.userId), role = OrganizationRole.MEMBER)
    }

    implicit val context = heimdalContextBuilder().build
    orgInviteCommander.inviteToOrganization(orgId = org.id.get, inviterId = org.ownerId, invitees = inviteRequests.toSeq)
  }

  def getCandidatesInfoHelper(orgId: Id[Organization])(implicit session: RSession): Map[Id[User], OrganizationMembershipInfo] = {
    val membershipCandidates = orgMembershipCandidateRepo.getAllByOrgId(orgId).map(_.userId)
    membershipCandidates.map { uid =>
      val numTotalKeeps = keepRepo.getCountByUser(uid)
      val numTotalChats = 42 // TODO: find a way to get the number of user chats
      uid -> OrganizationMembershipInfo(numTotalKeeps, numTotalChats)
    }.toMap
  }

  def getCandidatesInfo(orgId: Id[Organization]): Map[Id[User], OrganizationMembershipInfo] = {
    db.readOnlyReplica { implicit session =>
      getCandidatesInfoHelper(orgId)
    }
  }

  def getCandidatesInfos(orgIds: Seq[Id[Organization]]): Map[Id[Organization], Map[Id[User], OrganizationMembershipInfo]] = {
    db.readOnlyReplica { implicit session =>
      orgIds.map { orgId => orgId -> getCandidatesInfoHelper(orgId) }.toMap
    }
  }

}
