package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.commanders.emails.EmailTemplateSender
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.TemplateOptions._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.{ UserPushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[OrganizationInviteCommanderImpl])
trait OrganizationInviteCommander {
  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[OrganizationMemberInvitation])(implicit eventContext: HeimdalContext): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]]
  def acceptInvitation(orgId: Id[Organization], userId: Id[User]): Either[OrganizationFail, OrganizationMembership]
  def declineInvitation(orgId: Id[Organization], userId: Id[User]): Seq[OrganizationInvite]
  // Creates a Universal Invite Link for an organization and inviter. Anyone with the link can join the Organization
  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], role: OrganizationRole = OrganizationRole.MEMBER, authToken: Option[String] = None): Either[OrganizationFail, (OrganizationInvite, Organization)]
}

@Singleton
class OrganizationInviteCommanderImpl @Inject() (db: Database,
    airbrake: AirbrakeNotifier,
    organizationRepo: OrganizationRepo,
    organizationMembershipCommander: OrganizationMembershipCommander,
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
    emailTemplateSender: EmailTemplateSender,
    kifiInstallationCommander: KifiInstallationCommander,
    organizationAvatarCommander: OrganizationAvatarCommander,
    organizationAnalytics: OrganizationAnalytics,
    implicit val publicIdConfig: PublicIdConfiguration) extends OrganizationInviteCommander with Logging {

  def inviteUsersToOrganization(orgId: Id[Organization], inviterId: Id[User], invitees: Seq[OrganizationMemberInvitation])(implicit eventContext: HeimdalContext): Future[Either[OrganizationFail, Seq[(Either[BasicUser, RichContact], OrganizationRole)]]] = {
    val inviterMembershipOpt = db.readOnlyMaster { implicit session =>
      organizationMembershipRepo.getByOrgIdAndUserId(orgId, inviterId)
    }
    inviterMembershipOpt match {
      case Some(inviterMembership) =>
        if (inviterMembership.hasPermission(OrganizationPermission.INVITE_MEMBERS)) {
          val inviteesByAddress = invitees.collect { case OrganizationMemberInvitation(Right(emailAddress), _, _) => emailAddress }
          val inviteesByUserId = invitees.collect { case OrganizationMemberInvitation(Left(userId), _, _) => userId }

          // contacts by email address
          val contactsByEmailAddressFut: Future[Map[EmailAddress, RichContact]] = {
            aBookClient.internKifiContacts(inviterId, inviteesByAddress.map(BasicContact(_)): _*).imap { kifiContacts =>
              (inviteesByAddress zip kifiContacts).toMap
            }
          }
          // contacts by userId
          val contactsByUserId = db.readOnlyMaster { implicit s =>
            basicUserRepo.loadAll(inviteesByUserId.toSet)
          }

          contactsByEmailAddressFut map { contactsByEmail =>
            val organizationMembersMap = db.readOnlyMaster { implicit session =>
              organizationMembershipRepo.getByOrgIdAndUserIds(orgId, inviteesByUserId.toSet).map { membership =>
                membership.userId -> membership
              }.toMap
            }
            val invitesForInvitees = invitees.filter {
              case OrganizationMemberInvitation(_, inviteRole, _) => inviterMembership.role >= inviteRole
            }.map {
              case OrganizationMemberInvitation(Left(inviteeId), inviteRole, msgOpt) =>
                organizationMembersMap.get(inviteeId) match {
                  case Some(inviteeMember) if (inviteeMember.role < inviteRole && inviteRole != OrganizationRole.OWNER) => // member needs upgrade
                    val modifyRequest = OrganizationMembershipModifyRequest(orgId = orgId, requesterId = inviterId, targetId = inviteeId, newRole = inviteRole)
                    organizationMembershipCommander.modifyMembership(modifyRequest)

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
              case OrganizationMemberInvitation(Right(email), inviteRole, msgOpt) =>
                val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, emailAddress = Some(email), role = inviteRole, message = msgOpt)
                val inviteeInfo = (Right(contactsByEmail(email)), inviteRole)
                Some((orgInvite, inviteeInfo))
            }

            val (invites, inviteesWithRole) = invitesForInvitees.flatten.unzip

            val (org, owner, inviter) = db.readOnlyMaster { implicit session =>
              val org = organizationRepo.get(orgId)
              val owner = basicUserRepo.load(org.ownerId)
              val inviter = userRepo.get(inviterId)
              (org, owner, inviter)
            }
            val persistedInvites = invites.flatMap(persistInvitation(_))

            sendInvitationEmails(persistedInvites, org, owner, inviter)
            organizationAnalytics.trackSentOrganizationInvites(inviterId, org, persistedInvites)

            Right(inviteesWithRole)
          }
        } else {
          Future.successful(Left(OrganizationFail("insufficient_permissions")))
        }
      case None =>
        Future.successful(Left(OrganizationFail("not_a_member")))
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

  def sendInvitationEmails(persistedInvites: Seq[OrganizationInvite], org: Organization, owner: BasicUser, inviter: User): Unit = {
    val (inviteesById, _) = persistedInvites.partition(_.userId.nonEmpty)

    // send notifications to kifi users only
    if (inviteesById.nonEmpty) {
      notifyInviteeAboutInvitationToJoinOrganization(org, owner, inviter, inviteesById.flatMap(_.userId).toSet)
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
        category = toRecipient.fold(_ => NotificationCategory.User.ORGANIZATION_INVITATION, _ => NotificationCategory.NonUser.ORGANIZATION_INVITATION),
        htmlTemplate = views.html.email.organizationInvitationPlain(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, org, authToken),
        textTemplate = Some(views.html.email.organizationInvitationText(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, org, authToken)),
        templateOptions = Seq(CustomLayout).toMap,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> fromAddress)),
        campaign = Some("na"),
        channel = Some("vf_email"),
        source = Some("organization_invite")
      )
      emailTemplateSender.send(emailToSend) map (Some(_))
    } getOrElse {
      airbrake.notify(s"OrganizationInvite does not have a recipient: $invite")
      Future.successful(None)
    }
  }

  def notifyInviteeAboutInvitationToJoinOrganization(org: Organization, orgOwner: BasicUser, inviter: User, invitees: Set[Id[User]]) {
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

  def authorizeInvitation(orgId: Id[Organization], userId: Id[User], authToken: String)(implicit session: RSession): Boolean = {
    organizationInviteRepo.getByOrgIdAndUserIdAndAuthToken(orgId, userId, authToken).nonEmpty
  }

  def acceptInvitation(orgId: Id[Organization], userId: Id[User]): Either[OrganizationFail, OrganizationMembership] = {
    val (invitations, membershipOpt) = db.readOnlyReplica { implicit session =>
      val userInvitations = organizationInviteRepo.getByOrgAndUserId(orgId, userId)
      val existingMembership = organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId)
      (userInvitations, existingMembership)
    }

    val updatedMembership: Either[OrganizationFail, OrganizationMembership] = membershipOpt match {
      case Some(membership) => Right(membership) // already a member
      case None => // new membership
        val addRequests = invitations.sortBy(_.role).reverse.map { currentInvitation =>
          OrganizationMembershipAddRequest(orgId, currentInvitation.inviterId, userId, currentInvitation.role)
        }
        val firstSuccess = addRequests.toStream.map(organizationMembershipCommander.addMembership(_))
          .find(_.isRight)
        firstSuccess.map(_.right.map(_.membership))
          .getOrElse(Left(OrganizationFail.NO_VALID_INVITATIONS))
    }
    updatedMembership.right.map { success =>
      // on success accept invitations
      db.readWrite { implicit s =>
        // Notify inviters on organization joined.
        notifyInviterOnOrganizationInvitationAcceptance(invitations, userRepo.get(userId), organizationRepo.get(orgId))
        invitations.foreach { invite =>
          organizationInviteRepo.save(invite.copy(state = OrganizationInviteStates.ACCEPTED))
        }
      }
      success
    }
  }

  def notifyInviterOnOrganizationInvitationAcceptance(invitesToAlert: Seq[OrganizationInvite], invitee: User, org: Organization): Unit = {
    val inviteeImage = s3ImageStore.avatarUrlByUser(invitee)
    val orgImageOpt = organizationAvatarCommander.getBestImage(org.id.get, ProcessedImageSize.Medium.idealSize)
    invitesToAlert foreach { invite =>
      val title = s"${invitee.firstName} has joined ${org.name}"
      val inviterId = invite.inviterId
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(inviterId),
        title = title,
        body = s"You invited ${invitee.fullName} to join ${org.name}.",
        linkText = s"See ${invitee.firstName}’s profile",
        linkUrl = s"https://www.kifi.com/${invitee.username.value}",
        imageUrl = inviteeImage,
        sticky = false,
        category = NotificationCategory.User.ORGANIZATION_JOINED,
        extra = Some(Json.obj(
          "member" -> BasicUser.fromUser(invitee),
          "organization" -> Json.toJson(OrganizationNotificationInfo.fromOrganization(org, orgImageOpt))
        ))
      )
      val canSendPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(inviterId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
      if (canSendPush) {
        elizaClient.sendUserPushNotification(
          userId = inviterId,
          message = title,
          recipient = invitee,
          pushNotificationExperiment = PushNotificationExperiment.Experiment1,
          category = UserPushNotificationCategory.NewOrganizationMember)
      }
    }
  }

  def declineInvitation(orgId: Id[Organization], userId: Id[User]): Seq[OrganizationInvite] = {
    db.readWrite { implicit s =>
      organizationInviteRepo.getByOrgIdAndUserId(orgId, userId)
        .map(inv => organizationInviteRepo.save(inv.copy(state = OrganizationInviteStates.DECLINED)))
    }
  }

  def universalInviteLink(orgId: Id[Organization], inviterId: Id[User], role: OrganizationRole = OrganizationRole.MEMBER, authToken: Option[String] = None): Either[OrganizationFail, (OrganizationInvite, Organization)] = {
    ???
  }
}
