package com.keepit.commanders

import com.google.inject.{ Provider, ImplementedBy, Inject, Singleton }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.commanders.emails.{ EmailTemplateSender, LibraryInviteEmailSender }
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.TemplateOptions._
import com.keepit.common.mail._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.{ ElizaServiceClient, LibraryPushNotificationCategory, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json.Json

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
class OrganizationInviteCommanderImpl @Inject() (db: Database,
    airbrake: AirbrakeNotifier,
    organizationRepo: OrganizationRepo,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo,
    aBookClient: ABookServiceClient,
    implicit val defaultContext: ExecutionContext,
    userIpAddressRepo: UserIpAddressRepo,
    userRepo: UserRepo,
    elizaClient: ElizaServiceClient,
    s3ImageStore: S3ImageStore,
    userEmailAddressRepo: UserEmailAddressRepo,
    emailTemplateSender: EmailTemplateSender) extends OrganizationInviteCommander with Logging {

  private def canInvite(membership: OrganizationMembership): Boolean = {
    membership.hasPermission(OrganizationPermission.INVITE_MEMBERS)
  }

  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[(Either[Id[User], EmailAddress], OrganizationRole, Option[String])]): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]] = {
    val inviterMembershipOpt = db.readOnlyMaster { implicit session =>
      organizationMembershipRepo.getByOrgIdAndUserId(orgId, inviterId)
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
            val (org, owner, inviter) = db.readOnlyMaster { implicit session =>
              val org = organizationRepo.get(orgId)
              val owner = basicUserRepo.load(org.ownerId)
              val inviter = userRepo.get(inviterId)
              (org, owner, inviter)
            }
            val persistedInvites = invites.flatMap { invite =>
              persistInvitation(invite)
            }

            sendInvitationEmails(persistedInvites, org, owner, inviter)
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

  // return whether the invitation was persisted or not.
  def persistInvitation(invite: OrganizationInvite): Option[OrganizationInvite] = {
    val inviterId = invite.inviterId
    val orgId = invite.organizationId
    val recipientId = invite.userId
    val recipientEmail = invite.emailAddress
    val shouldInsert = db.readOnlyMaster { implicit s =>
      (recipientId, recipientEmail) match {
        case (Some(userId), _) =>
          organizationInviteRepo.getLastSentByOrgIdAndInviterIdAndUserId(orgId, inviterId, userId, Set(OrganizationInviteStates.ACTIVE))
        case (_, Some(email)) =>
          organizationInviteRepo.getLastSentByOrgIdAndInviterIdAndEmailAddress(orgId, inviterId, email, Set(OrganizationInviteStates.ACTIVE))
        case _ => None
      }
    }.map { lastInvite =>
      // determine whether to resend invitation.
      lastInvite.role != invite.role || lastInvite.createdAt.plusMinutes(5).isBefore(invite.createdAt)
    }.getOrElse(true)
    shouldInsert match {
      case true => db.readWrite { implicit s =>
        Some(organizationInviteRepo.save(invite))
      }
      case false => None
    }
  }

  def sendInvitationEmails(persistedInvites: Seq[OrganizationInvite], org: Organization, owner: BasicUser, inviter: User) {
    val (inviteesById, _) = persistedInvites.partition(_.userId.nonEmpty)

    // send notifications to kifi users only
    if (inviteesById.nonEmpty) {
      notifyInviteeAboutInvitationToJoinLibrary(org, owner, inviter, inviteesById.flatMap(_.userId).toSet)
    }

    // send emails to both users & non-users
    persistedInvites.foreach { invite =>
      sendInvite(invite, org)
    }
  }

  def sendInvite(invite: OrganizationInvite, org: Organization): Future[Option[ElectronicMail]] = {
    val toRecipientOpt: Option[Either[Id[User], EmailAddress]] =
      if (invite.userId.isDefined) Some(Left(invite.userId.get))
      else if (invite.emailAddress.isDefined) Some(Right(invite.emailAddress.get))
      else None

    toRecipientOpt map { toRecipient =>
      val trimmedInviteMsg = invite.message map (_.trim) filter (_.nonEmpty)
      val fromUserId = invite.inviterId
      val fromAddress = db.readOnlyReplica { implicit session => userEmailAddressRepo.getByUser(invite.inviterId).address }
      val authToken = invite.authToken
      val emailToSend = EmailToSend(
        fromName = Some(Left(invite.inviterId)),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = s"Please join our organization ${org.name} on Kifi!",
        to = toRecipient,
        category = toRecipient.fold(_ => NotificationCategory.User.LIBRARY_INVITATION, _ => NotificationCategory.NonUser.LIBRARY_INVITATION),
        htmlTemplate = views.html.email.organizationInvitationPlain(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, org, authToken),
        textTemplate = Some(views.html.email.organizationInvitationText(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, org, authToken)),
        templateOptions = Seq(CustomLayout).toMap,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> fromAddress)),
        campaign = Some("na"),
        channel = Some("vf_email"),
        source = Some("library_invite")
      )
      emailTemplateSender.send(emailToSend) map (Some(_))
    } getOrElse {
      airbrake.notify(s"LibraryInvite does not have a recipient: $invite")
      Future.successful(None)
    }
  }

  def notifyInviteeAboutInvitationToJoinLibrary(org: Organization, orgOwner: BasicUser, inviter: User, invitees: Set[Id[User]]): Unit = {
    val userImage = s3ImageStore.avatarUrlByUser(inviter)
    val orgLink = s"""https://www.kifi.com/${org.name}"""

    elizaClient.sendGlobalNotification( //push sent
      userIds = invitees,
      title = s"${inviter.firstName} ${inviter.lastName} invited you to join ${org.name}!",
      body = s"Help ${org.name} by sharing your knowledge with them.",
      linkText = "Let's do it!",
      linkUrl = orgLink,
      imageUrl = userImage,
      sticky = false,
      category = NotificationCategory.User.ORGANIZATION_INVITATION
    )

    // TODO: handle push notifications to mobile.
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
