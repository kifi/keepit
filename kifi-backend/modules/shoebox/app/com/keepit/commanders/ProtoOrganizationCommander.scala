package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.abook.model.RichContact
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ProtoOrganizationCommanderImpl])
trait ProtoOrganizationCommander {
  def addProtoMembers(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit]
  def removeProtoMembers(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit]
  def inviteProtoMembers(orgId: Id[Organization]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]]
}

@Singleton
class ProtoOrganizationCommanderImpl @Inject() (
    db: Database,
    orgRepo: OrganizationRepo,
    orgMembershipRepo: OrganizationMembershipRepo,
    protoOrgMembershipRepo: ProtoOrganizationMembershipRepo,
    orgInviteCommander: OrganizationInviteCommander,
    implicit val executionContext: ExecutionContext,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends ProtoOrganizationCommander with Logging {

  def addProtoMembers(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit] = SafeFuture {
    db.readWrite { implicit session =>
      val existingProtoMemberships = protoOrgMembershipRepo.getAllByOrgId(orgId, states = ProtoOrganizationMembershipStates.all)

      val inactiveProtoMemberships = existingProtoMemberships.filter(_.state == ProtoOrganizationMembershipStates.INACTIVE)
      val protoMembershipsToBeReactivated = inactiveProtoMemberships.filter(m => userIds.contains(m.userId))
      protoMembershipsToBeReactivated.foreach { m =>
        protoOrgMembershipRepo.save(m.withState(ProtoOrganizationMembershipStates.ACTIVE))
      }

      val userIdsToBeAdded = userIds -- existingProtoMemberships.map(_.userId).toSet
      userIdsToBeAdded.foreach { uid =>
        protoOrgMembershipRepo.save(ProtoOrganizationMembership(orgId = orgId, userId = uid))
      }
    }
  }

  def removeProtoMembers(orgId: Id[Organization], userIds: Set[Id[User]]): Future[Unit] = SafeFuture {
    db.readWrite { implicit session =>
      val existingProtoMemberships = protoOrgMembershipRepo.getAllByOrgId(orgId).map(m => m.userId -> m).toMap
      val toBeDeactivated = userIds intersect existingProtoMemberships.keySet
      toBeDeactivated.foreach { uid =>
        protoOrgMembershipRepo.deactivate(existingProtoMemberships(uid))
      }
    }
  }

  def inviteProtoMembers(orgId: Id[Organization]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]] = {
    val (org, existingProtoMemberships) = db.readOnlyReplica { implicit session =>
      (orgRepo.get(orgId), protoOrgMembershipRepo.getAllByOrgId(orgId).toSet)
    }

    val inviteRequests = existingProtoMemberships.map { m =>
      OrganizationMemberInvitation(invited = Left(m.userId), role = OrganizationRole.MEMBER)
    }

    implicit val context = heimdalContextBuilder().build
    orgInviteCommander.inviteToOrganization(orgId = org.id.get, inviterId = org.ownerId, invitees = inviteRequests.toSeq)
  }
}
