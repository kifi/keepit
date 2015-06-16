package com.keepit.commanders

import com.google.inject.Singleton
import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.common.core._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ ElectronicMail, BasicContact, EmailAddress }
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.social.BasicUser

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@ImplementedBy(classOf[OrganizationInviteCommanderImpl])
trait OrganizationInviteCommander {
  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], OrganizationRole, Option[String])]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]]
  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: Option[String] = None): Either[OrganizationFail, (Organization, OrganizationMembership)]
  def declineInvitation(orgId: Id[Organization], userId: Id[User])
  // Creates a Universal Invite Link for an organization and inviter. Anyone with the link can join the Organization
  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], access: OrganizationRole = OrganizationRole.MEMBER, authToken: Option[String] = None)
}

@Singleton
class OrganizationInviteCommanderImpl @Inject() (db: Database,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo,
    aBookClient: ABookServiceClient,
    implicit val defaultContext: ExecutionContext,
    userIpAddressRepo: UserIpAddressRepo,
    userRepo: UserRepo) extends OrganizationInviteCommander with Logging {

  private def canInvite(membership: OrganizationMembership): Boolean = {
    membership.hasPermission(OrganizationPermission.INVITE_MEMBERS)
  }

  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], OrganizationRole, Option[String])]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]] = {
    val (org, inviterMembershipOpt) = db.readOnlyMaster { implicit session =>
      val org = organizationRepo.get(orgId)
      val membership = organizationMembershipRepo.getByOrgIdAndUserId(orgId, inviterId)
      (org, membership)
    }
    inviterMembershipOpt match {
      case Some(inviterMembership) =>
        if (canInvite(inviterMembership)) {
          val inviteesByAddress = invitees.collect { case (Right(emailAddress), _, _) => emailAddress }
          val inviteesByUserId = invitees.collect { case (Left(userId), _, _) => userId }

          // contacts by email address
          val futureContactsByEmailAddress: Future[Map[EmailAddress, RichContact]] = {
            aBookClient.internKifiContacts(inviterId, inviteesByAddress.map(BasicContact(_)): _*).imap { kifiContacts =>
              (inviteesByAddress zip kifiContacts).toMap
            }
          }
          // contacts by userId
          val contactsByUserId = db.readOnlyMaster { implicit s =>
            basicUserRepo.loadAll(inviteesByUserId.toSet)
          }

          futureContactsByEmailAddress map { contactsByEmail =>
            val organizationMembersMap = db.readOnlyMaster { implicit session =>
              organizationMembershipRepo.getByOrgIdAndUserIds(orgId, inviteesByUserId.toSet).map { membership =>
                membership.userId -> membership
              }.toMap
            }
            val invitesForInvitees = for ((recipient, inviteRole, msgOpt) <- invitees) yield {
              if (inviterMembership.role >= inviteRole) {
                recipient match {
                  case Left(inviteeId) => {
                    organizationMembersMap.get(inviteeId) match {
                      case Some(inviteeMember) if (inviteeMember.role < inviteRole && inviteRole != OrganizationRole.OWNER) => // member needs upgrade
                        db.readWrite { implicit session =>
                          organizationMembershipRepo.save(inviteeMember.copy(role = inviteRole))
                        }
                        val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, userId = Some(inviteeId), role = inviteRole, message = msgOpt)
                        val inviteeInfo = (Left(contactsByUserId(inviteeId)), inviteRole)
                        Some((orgInvite, inviteeInfo))
                      case Some(inviteeMember) => // don't demote members through an invitation.
                        None
                      case _ => // user not a member of org yet
                        val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, userId = Some(inviteeId), role = inviteRole, message = msgOpt)
                        val inviteeInfo = (Left(contactsByUserId(inviteeId)), inviteRole)
                        Some((orgInvite, inviteeInfo))
                    }
                  }
                  case Right(email) => {
                    val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, emailAddress = Some(email), role = inviteRole, message = msgOpt)
                    val inviteeInfo = (Right(contactsByEmail(email)), inviteRole)
                    Some((orgInvite, inviteeInfo))
                  }
                }
              } else {
                log.warn(s"Inviter $inviterId attempting to grant role inviter does not have.")
                None
              }
            }

            val (invites, inviteesWithRole): (Seq[OrganizationInvite], Seq[(Either[BasicUser, RichContact], OrganizationRole)]) = invitesForInvitees.flatten.unzip

            // TODO: still need to write code for persisting invitations / sending invitation email / tracking sent invitation
            def persistInvitations = ???
            def sendInvitationEmail = ???
            def trackSentInvitation = ???

            Right(inviteesWithRole)
          }
        } else {
          Future.successful(Left(OrganizationFail(200, "cannot_invite_members")))
        }
      case None =>
        Future.successful(Left(OrganizationFail(401, "inviter_not_a_member")))
    }
  }

  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: Option[String] = None): Either[OrganizationFail, (Organization, OrganizationMembership)] = {
    ???
  }

  def declineInvitation(orgId: Id[Organization], userId: Id[User]) = {
    ???
  }

  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], access: OrganizationRole = OrganizationRole.MEMBER, authToken: Option[String] = None) = {
    ???
  }
}
