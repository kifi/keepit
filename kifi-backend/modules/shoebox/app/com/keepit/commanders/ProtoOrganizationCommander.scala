package com.keepit.commanders

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ProtoOrganizationCommanderImpl])
trait ProtoOrganizationCommander {
  def createProtoOrganization(ownerId: Id[User], name: String): ProtoOrganization
  def addMembers(protoOrgId: Id[ProtoOrganization], userIds: Set[Id[User]]): Unit
  def instantiateOrganization(protoOrg: ProtoOrganization): Future[Either[OrganizationFail, OrganizationCreateResponse]]
}

@Singleton
class ProtoOrganizationCommanderImpl @Inject() (
    db: Database,
    protoOrgRepo: ProtoOrganizationRepo,
    protoOrgMembershipRepo: ProtoOrganizationMembershipRepo,
    orgCommander: OrganizationCommander,
    //orgInviteCommander: OrganizationInviteCommander,
    implicit val executionContext: ExecutionContext,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends ProtoOrganizationCommander with Logging {

  def createProtoOrganization(ownerId: Id[User], name: String): ProtoOrganization = {
    db.readWrite { implicit session => protoOrgRepo.save(ProtoOrganization(ownerId = ownerId, name = name)) }
  }
  def addMembers(protoOrgId: Id[ProtoOrganization], userIds: Set[Id[User]]): Unit = {
    db.readWrite { implicit session =>
      val existingMemberIds = protoOrgMembershipRepo.getAllByProtoOrganization(protoOrgId).collect {
        case membership if membership.userId.nonEmpty => membership.userId.get
      }.toSet
      (userIds -- existingMemberIds).foreach { uid =>
        protoOrgMembershipRepo.save(ProtoOrganizationMembership(protoOrgId = protoOrgId, userId = Some(uid)))
      }
    }
  }
  def instantiateOrganization(protoOrg: ProtoOrganization): Future[Either[OrganizationFail, OrganizationCreateResponse]] = {
    val invites = db.readOnlyReplica { implicit session =>
      protoOrgMembershipRepo.getAllByProtoOrganization(protoOrg.id.get)
    }
    val ownerId = protoOrg.ownerId
    val initialValues = OrganizationModifications(
      name = Some(protoOrg.name),
      description = protoOrg.description
    )
    val createRequest = OrganizationCreateRequest(requesterId = ownerId, initialValues = initialValues)
    orgCommander.createOrganization(createRequest) match {
      case Left(fail) => Future.successful(Left(fail))
      case Right(createResponse) =>
        val org = createResponse.newOrg
        val inviteRequests = invites.collect {
          case user if user.userId.nonEmpty => OrganizationMemberInvitation(invited = Left(user.userId.get), role = OrganizationRole.MEMBER)
          case email if email.emailAddress.nonEmpty => OrganizationMemberInvitation(invited = Right(email.emailAddress.get), role = OrganizationRole.MEMBER)
        }
        /*
        implicit val context = heimdalContextBuilder().build
        val inviteResult = orgInviteCommander.inviteToOrganization(orgId = org.id.get, inviterId = ownerId, invitees = inviteRequests)
        inviteResult map {
          case Left(fail) => Left(fail)
          case Right(_) => Right(createResponse)
        }
        */
        Future.successful(Right(createResponse))
    }
  }
}
