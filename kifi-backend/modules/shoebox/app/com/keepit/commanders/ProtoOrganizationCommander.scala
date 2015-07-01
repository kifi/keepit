package com.keepit.commanders

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.logging.Logging
import com.keepit.model._

import scala.concurrent.Await
import scala.concurrent.duration._

@ImplementedBy(classOf[ProtoOrganizationCommanderImpl])
trait ProtoOrganizationCommander {
  def instantiateProtoOrganization(protoOrg: ProtoOrganization): Either[OrganizationFail, OrganizationCreateResponse]
}

@Singleton
class ProtoOrganizationCommanderImpl @Inject() (
  protoOrgRepo: ProtoOrganizationRepo,
  orgCommander: OrganizationCommander,
  orgMembershipCommander: OrganizationMembershipCommander,
  orgInviteCommander: OrganizationInviteCommander) extends ProtoOrganizationCommander with Logging {

  def instantiateProtoOrganization(protoOrg: ProtoOrganization): Either[OrganizationFail, OrganizationCreateResponse] = {
    val ownerId = protoOrg.ownerId
    val initialValues = OrganizationModifications(
      name = Some(protoOrg.name),
      description = protoOrg.description
    )
    val createRequest = OrganizationCreateRequest(requesterId = ownerId, initialValues = initialValues)
    orgCommander.createOrganization(createRequest) match {
      case Left(fail) => Left(fail)
      case Right(createResponse) =>
        val org = createResponse.newOrg
        val membershipAddRequests = protoOrg.members.map { userId =>
          OrganizationMembershipAddRequest(orgId = org.id.get, requesterId = ownerId, targetId = userId, newRole = OrganizationRole.MEMBER)
        }
        orgMembershipCommander.addMemberships(membershipAddRequests) match {
          case Left(fail) => Left(fail)
          case Right(_) =>
            val emailInvites = protoOrg.inviteeEmails.map { email =>
              OrganizationMemberInvitation(invited = Right(email), role = OrganizationRole.MEMBER)
            }
            Await.result(orgInviteCommander.inviteToOrganization(orgId = org.id.get, inviterId = ownerId, invitees = emailInvites), 10 seconds) match {
              case Left(fail) => Left(fail)
              case Right(_) => Right(createResponse)
            }
        }
    }
  }

}
