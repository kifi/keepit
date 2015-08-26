package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.commanders.emails.EmailTemplateSender
import com.keepit.common.time._
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail._
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.TemplateOptions._
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.eliza.{ ElizaServiceClient, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationPermission.INVITE_MEMBERS
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ OrgInviteAccepted, OrgNewInvite }
import com.keepit.social.BasicUser
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[OrganizationInviteCommanderImpl])
trait OrganizationInviteCommander {
  def convertPendingInvites(emailAddress: EmailAddress, userId: Id[User])(implicit session: RWSession): Unit
  def inviteToOrganization(orgInvite: OrganizationInviteSendRequest)(implicit eventContext: HeimdalContext): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]]
  def cancelOrganizationInvites(request: OrganizationInviteCancelRequest): Either[OrganizationFail, OrganizationInviteCancelResponse]
  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: String)(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationMembership]
  def declineInvitation(orgId: Id[Organization], userId: Id[User]): Seq[OrganizationInvite]
  def createGenericInvite(orgId: Id[Organization], inviterId: Id[User])(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationInvite] // creates a Universal Invite Link for an organization and inviter. Anyone with the link can join the Organization
  def getInvitesByOrganizationId(orgId: Id[Organization]): Set[OrganizationInvite]
  def getLastSentByOrganizationIdAndInviteeId(orgId: Id[Organization], inviteeId: Id[User]): Option[OrganizationInvite]
  def getInviteByOrganizationIdAndAuthToken(orgId: Id[Organization], authToken: String): Option[OrganizationInvite]
  def suggestMembers(userId: Id[User], orgId: Id[Organization], query: Option[String], limit: Int): Future[Seq[MaybeOrganizationMember]]
  def isAuthValid(orgId: Id[Organization], authToken: String): Boolean
  def getViewerInviteInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Option[OrganizationInviteInfo]
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
    typeaheadCommander: TypeaheadCommander,
    organizationAnalytics: OrganizationAnalytics,
    userExperimentRepo: UserExperimentRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends OrganizationInviteCommander with Logging {

  private def getValidationError(request: OrganizationInviteRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val requesterOpt = organizationMembershipRepo.getByOrgIdAndUserId(request.orgId, request.requesterId)

    val validateRequester = requesterOpt match {
      case None => Some(OrganizationFail.NOT_A_MEMBER)
      case Some(membership) if !membership.hasPermission(OrganizationPermission.INVITE_MEMBERS) => Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      case _ => None
    }

    validateRequester match {
      case Some(invalidRequester) => Some(invalidRequester)
      case None =>
        request match {
          case OrganizationInviteSendRequest(orgId, requesterId, targetEmails, targetUsers, _) => {
            val existingMembers = organizationMembershipRepo.getByOrgIdAndUserIds(orgId, targetUsers)

            if (existingMembers.nonEmpty) Some(OrganizationFail.ALREADY_A_MEMBER)
            else None
          }
          case OrganizationInviteCancelRequest(orgId, requesterId, targetEmails, targetUsers) => {
            val existingInvites = organizationInviteRepo.getAllByOrganization(request.orgId)
            val doInvitesExist = targetEmails.forall(email => existingInvites.exists(_.emailAddress.contains(email))) &&
              targetUsers.forall(userId => existingInvites.exists(_.userId.contains(userId)))

            if (!doInvitesExist) Some(OrganizationFail.INVITATION_NOT_FOUND)
            else None
          }
        }
    }
  }

  def convertPendingInvites(emailAddress: EmailAddress, userId: Id[User])(implicit session: RWSession): Unit = {
    val hasOrgInvite = organizationInviteRepo.getByEmailAddress(emailAddress).map { invitation =>
      organizationInviteRepo.save(invitation.copy(userId = Some(userId)))
      invitation.state
    } contains OrganizationInviteStates.ACTIVE
  }

  def inviteToOrganization(orgInvite: OrganizationInviteSendRequest)(implicit eventContext: HeimdalContext): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]] = {
    val OrganizationInviteSendRequest(orgId, inviterId, inviteeAddresses, inviteeUserIds, message) = orgInvite

    val inviterMembershipOpt = db.readOnlyMaster { implicit session =>
      organizationMembershipRepo.getByOrgIdAndUserId(orgId, inviterId)
    }

    val failOpt = db.readOnlyReplica { implicit session => getValidationError(orgInvite) }

    failOpt match {
      case Some(fail) => Future.successful(Left(fail))
      case _ => {
        val contactsByEmailAddressFut: Future[Map[EmailAddress, RichContact]] = {
          aBookClient.internKifiContacts(inviterId, inviteeAddresses.map(BasicContact(_)).toSeq: _*).imap { kifiContacts =>
            (inviteeAddresses zip kifiContacts).toMap
          }
        }

        val basicUserByUserId: Map[Id[User], BasicUser] = db.readOnlyMaster { implicit session =>
          basicUserRepo.loadAll(inviteeUserIds.toSet)
        }

        contactsByEmailAddressFut map { contactsByEmail =>
          val addMembers = inviteeUserIds.map { userId =>
            val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, userId = Some(userId), message = message)
            val inviteeInfo: Either[BasicUser, RichContact] = Left(basicUserByUserId(userId))
            (orgInvite, inviteeInfo)
          }
          val addByEmail = inviteeAddresses.map { emailAddress =>
            val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, emailAddress = Some(emailAddress), message = message)
            val inviteeInfo: Either[BasicUser, RichContact] = Right(contactsByEmail(emailAddress))
            (orgInvite, inviteeInfo)
          }
          val invitesAndInviteeInfos = addMembers ++ addByEmail

          val (invites, inviteeInfos) = invitesAndInviteeInfos.unzip

          val (org, owner, inviter) = db.readOnlyMaster { implicit session =>
            val org = organizationRepo.get(orgId)
            val owner = basicUserRepo.load(org.ownerId)
            val inviter = userRepo.get(inviterId)
            (org, owner, inviter)
          }
          val persistedInvites = invites.flatMap(persistInvitation)

          sendInvitationEmailsAndNotifications(persistedInvites, org, owner, inviter)
          organizationAnalytics.trackSentOrganizationInvites(inviterId, org, persistedInvites)

          Right(inviteeInfos)
        }
      }
    }
  }

  // return whether the invitation was persisted or not.
  def persistInvitation(invite: OrganizationInvite): Option[OrganizationInvite] = {
    val OrganizationInvite(_, _, _, _, _, orgId, inviterId, recipientId, recipientEmail, _, _, _) = invite
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
      lastInvite.createdAt.plusMinutes(5).isBefore(invite.createdAt) // 5 minutes seems too short?
    }.getOrElse(true)
    if (shouldInsert) {
      db.readWrite { implicit s => Some(organizationInviteRepo.save(invite)) }
    } else {
      None
    }
  }

  def sendInvitationEmailsAndNotifications(persistedInvites: Set[OrganizationInvite], org: Organization, owner: BasicUser, inviter: User): Unit = {
    val (inviteesById, _) = persistedInvites.partition(_.userId.nonEmpty)

    // send notifications to kifi users only
    if (inviteesById.nonEmpty) {
      notifyInviteeAboutInvitationToJoinOrganization(org, owner, inviter, inviteesById.flatMap(_.userId))
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
      val authToken = invite.authToken
      val emailToSend = EmailToSend(
        fromName = Some(Left(invite.inviterId)),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = s"Please join our ${org.name} team on Kifi!",
        to = toRecipient,
        category = toRecipient.fold(_ => NotificationCategory.User.ORGANIZATION_INVITATION, _ => NotificationCategory.NonUser.ORGANIZATION_INVITATION),
        htmlTemplate = views.html.email.organizationInvitationPlain(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, org, authToken),
        textTemplate = Some(views.html.email.organizationInvitationText(toRecipient.left.toOption, fromUserId, trimmedInviteMsg, org, authToken)),
        templateOptions = Seq(CustomLayout).toMap,
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
    val orgLink = s"""https://www.kifi.com/${org.handle.value}"""

    elizaClient.sendGlobalNotification( //push sent
      userIds = invitees,
      title = s"${inviter.firstName} ${inviter.lastName} invited you to join ${org.abbreviatedName}!",
      body = s"Help ${org.abbreviatedName} by sharing your knowledge with them.",
      linkText = "Let's do it!",
      linkUrl = orgLink,
      imageUrl = userImage,
      sticky = false,
      category = NotificationCategory.User.ORGANIZATION_INVITATION
    )
    invitees.foreach { invitee =>
      elizaClient.sendNotificationEvent(OrgNewInvite(
        Recipient(invitee),
        currentDateTime,
        inviter.id.get,
        org.id.get
      ))
    }

    // TODO: handle push notifications to mobile.
  }

  def authorizeInvitation(orgId: Id[Organization], userId: Id[User], authToken: String)(implicit session: RSession): Boolean = {
    organizationInviteRepo.getByOrgIdAndUserIdAndAuthToken(orgId, userId, authToken).nonEmpty
  }

  def cancelOrganizationInvites(request: OrganizationInviteCancelRequest): Either[OrganizationFail, OrganizationInviteCancelResponse] = {
    db.readWrite { implicit session => cancelOrganizationInvitesHelper(request) }
  }
  private def cancelOrganizationInvitesHelper(request: OrganizationInviteCancelRequest)(implicit session: RWSession): Either[OrganizationFail, OrganizationInviteCancelResponse] = {
    getValidationError(request) match {
      case Some(fail) => Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      case None =>
        val existingInvites = organizationInviteRepo.getAllByOrganization(request.orgId)
        val emailInvitesToCancel = existingInvites.filter { inv =>
          inv.emailAddress.exists { email => request.targetEmails.contains(email) }
        }
        val userIdInvitesToCancel = existingInvites.filter { inv =>
          inv.userId.exists { userId => request.targetUserIds.contains(userId) }
        }
        emailInvitesToCancel.foreach(organizationInviteRepo.deactivate)
        userIdInvitesToCancel.foreach(organizationInviteRepo.deactivate)

        Right(OrganizationInviteCancelResponse(
          request,
          cancelledEmails = emailInvitesToCancel.map(_.emailAddress.get),
          cancelledUserIds = userIdInvitesToCancel.map(_.userId.get))
        )
    }
  }

  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authToken: String)(implicit context: HeimdalContext): Either[OrganizationFail, OrganizationMembership] = {
    val (invitations, membershipOpt) = db.readOnlyReplica { implicit session =>
      val userInvitations = organizationInviteRepo.getByOrgAndUserId(orgId, userId)
      val existingMembership = organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId)
      val universalInvitation = organizationInviteRepo.getByOrgIdAndAuthToken(orgId, authToken)
      val allInvitations = universalInvitation match {
        case Some(invitation) if !userInvitations.contains(invitation) => userInvitations.+:(invitation)
        case _ => userInvitations
      }
      (allInvitations, existingMembership)
    }

    val updatedMembership: Either[OrganizationFail, OrganizationMembership] = membershipOpt match {
      case Some(membership) => Right(membership) // already a member
      case None => // new membership
        val addRequests = invitations.sortBy(_.role).reverse.map { currentInvitation =>
          OrganizationMembershipAddRequest(orgId, currentInvitation.inviterId, userId, currentInvitation.role)
        }

        val firstSuccess = addRequests.toStream.map(organizationMembershipCommander.addMembership)
          .find(_.isRight)
        firstSuccess.map(_.right.map(_.membership))
          .getOrElse(Left(OrganizationFail.NO_VALID_INVITATIONS))
    }
    updatedMembership.right.map { success =>
      // on success accept invitations
      db.readWrite { implicit s =>
        // Notify inviters on organization joined.
        val organization = organizationRepo.get(orgId)
        notifyInviterOnOrganizationInvitationAcceptance(invitations, userRepo.get(userId), organization)
        invitations.foreach { invite =>
          organizationInviteRepo.save(invite.accepted.withState(OrganizationInviteStates.INACTIVE))
          if (authToken.nonEmpty) organizationAnalytics.trackAcceptedEmailInvite(organization, invite.inviterId, invite.userId, invite.emailAddress)
        }
      }
      success
    }
  }

  def notifyInviterOnOrganizationInvitationAcceptance(invitesToAlert: Seq[OrganizationInvite], invitee: User, org: Organization): Unit = {
    val inviteeImage = s3ImageStore.avatarUrlByUser(invitee)
    val orgImageOpt = organizationAvatarCommander.getBestImageByOrgId(org.id.get, ProcessedImageSize.Medium.idealSize)
    invitesToAlert foreach { invite =>
      val title = s"${invitee.firstName} accepted your invitation to join ${org.abbreviatedName}!"
      val inviterId = invite.inviterId
      elizaClient.sendGlobalNotification( //push sent
        userIds = Set(inviterId),
        title = title,
        body = s"Click here to view ${org.abbreviatedName}’s libraries.",
        linkText = s"See ${org.abbreviatedName}’s libraries",
        linkUrl = s"https://www.kifi.com/${org.handle.value}",
        imageUrl = inviteeImage,
        sticky = false,
        category = NotificationCategory.User.ORGANIZATION_JOINED,
        extra = Some(Json.obj(
          "member" -> BasicUser.fromUser(invitee),
          "organization" -> Json.toJson(OrganizationNotificationInfoBuilder.fromOrganization(org, orgImageOpt))
        ))
      )
      elizaClient.sendNotificationEvent(OrgInviteAccepted(
        Recipient(inviterId),
        currentDateTime,
        invitee.id.get,
        org.id.get
      ))
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
        .map(inv => organizationInviteRepo.save(inv.copy(decision = InvitationDecision.DECLINED, state = OrganizationInviteStates.INACTIVE)))
    }
  }

  def createGenericInvite(orgId: Id[Organization], inviterId: Id[User])(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationInvite] = {
    db.readWrite { implicit session =>
      val membershipOpt = organizationMembershipRepo.getByOrgIdAndUserId(orgId, inviterId)
      membershipOpt match {
        case Some(membership) if membership.hasPermission(OrganizationPermission.INVITE_MEMBERS) =>
          val invite = organizationInviteRepo.save(OrganizationInvite(organizationId = orgId, inviterId = inviterId))

          // tracking
          organizationAnalytics.trackSentOrganizationInvites(inviterId, organizationRepo.get(orgId), Set(invite))
          Right(invite)
        case Some(membership) => Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
        case None => Left(OrganizationFail.NOT_A_MEMBER)
      }
    }
  }

  def getInvitesByOrganizationId(orgId: Id[Organization]): Set[OrganizationInvite] = {
    db.readOnlyReplica { implicit session =>
      organizationInviteRepo.getAllByOrganization(orgId)
    }
  }

  def getInviteByOrganizationIdAndAuthToken(orgId: Id[Organization], authToken: String): Option[OrganizationInvite] = {
    db.readOnlyReplica { implicit session =>
      organizationInviteRepo.getByOrgIdAndAuthToken(orgId, authToken)
    }
  }

  def getLastSentByOrganizationIdAndInviteeId(orgId: Id[Organization], inviteeId: Id[User]): Option[OrganizationInvite] = {
    db.readOnlyReplica { implicit session =>
      organizationInviteRepo.getLastSentByOrgIdAndUserId(orgId, inviteeId)
    }
  }

  def suggestMembers(userId: Id[User], orgId: Id[Organization], query: Option[String], limit: Int): Future[Seq[MaybeOrganizationMember]] = {
    val friendsAndContactsFut = query.map(_.trim).filter(_.nonEmpty) match {
      case Some(validQuery) => typeaheadCommander.searchFriendsAndContacts(userId, validQuery, Some(limit))
      case None =>
        val memberCount = db.readOnlyMaster { implicit s => organizationMembershipRepo.countByOrgId(orgId) }
        Future.successful(typeaheadCommander.suggestFriendsAndContacts(userId, Some(limit + memberCount)))
    }
    val activeInvites = db.readOnlyMaster { implicit session =>
      organizationInviteRepo.getAllByOrgIdAndDecisions(orgId, Set(InvitationDecision.PENDING, InvitationDecision.DECLINED))
    }

    val invitedUsers = activeInvites.groupBy(_.userId).collect {
      case (Some(userId), invites) =>
        val access = invites.map(_.role).maxBy(_.priority)
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
        userId -> (access, lastInvitedAt)
    }

    val invitedEmailAddresses = activeInvites.groupBy(_.emailAddress).collect {
      case (Some(emailAddress), invites) =>
        val access = invites.map(_.role).maxBy(_.priority)
        val lastInvitedAt = invites.map(_.createdAt).maxBy(_.getMillis)
        emailAddress -> (access, lastInvitedAt)
    }

    friendsAndContactsFut.map {
      case (users, contacts) =>
        val existingMembers = {
          val userIds = users.map(_._1).toSet
          db.readOnlyMaster { implicit session =>
            organizationMembershipRepo.getByOrgIdAndUserIds(orgId, userIds)
          }.map(_.userId).toSet
        }
        val suggestedUsers = users.collect {
          case (userId, basicUser) if !existingMembers.contains(userId) => // if we decide to add "role" to invites, existing members should not be filtered (they may be promoted via an invite)
            val (role, lastInvitedAt) = invitedUsers.get(userId) match {
              case Some((role, lastInvitedAt)) => (role, Some(lastInvitedAt))
              case None => invitedUsers.get(userId) match {
                case Some((role, lastInvitedAt)) => (role, Some(lastInvitedAt))
                case None => (OrganizationRole.MEMBER, None) // default invite role
              }
            }
            MaybeOrganizationMember(Left(basicUser), role, lastInvitedAt)
        }

        val suggestedEmailAddresses = contacts.map { contact =>
          val (role, lastInvitedAt) = invitedEmailAddresses.get(contact.email) match {
            case Some((role, lastInvitedAt)) => (role, Some(lastInvitedAt))
            case None => (OrganizationRole.MEMBER, None)
          }
          MaybeOrganizationMember(Right(contact), role, lastInvitedAt)
        }
        (suggestedUsers ++ suggestedEmailAddresses).take(limit)
    }
  }

  def isAuthValid(orgId: Id[Organization], authToken: String): Boolean = {
    db.readOnlyReplica { implicit session => organizationInviteRepo.getByOrgIdAndAuthToken(orgId, authToken) }.isDefined
  }

  def getViewerInviteInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Option[OrganizationInviteInfo] = {
    val userInviteOpt = viewerIdOpt.flatMap(getLastSentByOrganizationIdAndInviteeId(orgId, _))
    val authTokenInviteOpt = authTokenOpt.flatMap(getInviteByOrganizationIdAndAuthToken(orgId, _))
    val inviteOpt = userInviteOpt.orElse(authTokenInviteOpt)
    inviteOpt.map { invite =>
      val inviter = db.readOnlyReplica { implicit session => basicUserRepo.load(invite.inviterId) }
      OrganizationInviteInfo.fromInvite(invite, inviter)
    }
  }
}
