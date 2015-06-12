package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.abook.model.RichContact
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.concurrent.Future

class OrganizationInviteCommander @Inject() () {

  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], LibraryAccess, Option[String])]): Future[Either[LibraryFail, Seq[(Either[BasicUser, RichContact], OrganizationAccess)]]] = ???

  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: Option[String] = None): Either[OrganizationFail, (Organization, OrganizationMembership)] = ???

  def declineInvitation(orgId: Id[Organization], userId: Id[User]) = ???

  // Creates a Universal Invite Link for an organization and inviter. Anyone with the link can join the Organization
  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], access: OrganizationAccess = OrganizationAccess.READ_WRITE, authToken: Option[String] = None) = ???
}
