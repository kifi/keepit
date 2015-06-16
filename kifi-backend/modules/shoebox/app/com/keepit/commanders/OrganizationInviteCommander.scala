package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.abook.model.RichContact
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[OrganizationInviteCommanderImpl])
trait OrganizationInviteCommander {
  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], OrganizationRole, Option[String])]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]]
  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: Option[String] = None): Either[OrganizationFail, (Organization, OrganizationMembership)]
  def declineInvitation(orgId: Id[Organization], userId: Id[User])
  // Creates a Universal Invite Link for an organization and inviter. Anyone with the link can join the Organization
  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], role: OrganizationRole = OrganizationRole.MEMBER, authToken: Option[String] = None): Either[OrganizationFail, (OrganizationInvite, Organization)]
}

@Singleton
class OrganizationInviteCommanderImpl @Inject() (
    db: Database,
    implicit val defaultContext: ExecutionContext,
    userIpAddressRepo: UserIpAddressRepo,
    userRepo: UserRepo) extends OrganizationInviteCommander with Logging {

  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], OrganizationRole, Option[String])]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]] = {
    ???
  }
  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: Option[String] = None): Either[OrganizationFail, (Organization, OrganizationMembership)] = {
    ???
  }
  def declineInvitation(orgId: Id[Organization], userId: Id[User]) = {
    ???
  }

  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], role: OrganizationRole = OrganizationRole.MEMBER, authToken: Option[String] = None): Either[OrganizationFail, (OrganizationInvite, Organization)] = {
    ???
  }
}
