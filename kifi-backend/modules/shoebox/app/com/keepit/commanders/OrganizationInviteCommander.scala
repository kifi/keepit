package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Inject, Provider, Singleton }
import com.keepit.abook.ABookServiceClient
import com.keepit.abook.model.RichContact
import com.keepit.commanders.emails.EmailTemplateSender
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.controller.UserRequest
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
import com.keepit.common.mail.template.helpers.{ fullName, organizationName }
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.common.util.{ LinkElement, DescriptionElements }
import com.keepit.eliza.{ OrgPushNotificationRequest, OrgPushNotificationCategory, ElizaServiceClient, PushNotificationExperiment, UserPushNotificationCategory }
import com.keepit.heimdal.{ ContextBoolean, ContextData, HeimdalContext }
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.{ OrgInviteAccepted, OrgNewInvite }
import com.keepit.search.SearchServiceClient
import com.keepit.slack.{ SlackClientWrapper, SlackActionFail }
import com.keepit.slack.models._
import com.keepit.social.BasicUser
import org.joda.time.ReadableDuration

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[OrganizationInviteCommanderImpl])
trait OrganizationInviteCommander {
  def inviteToOrganization(orgInvite: OrganizationInviteSendRequest)(implicit eventContext: HeimdalContext): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]]
  def cancelOrganizationInvites(request: OrganizationInviteCancelRequest): Either[OrganizationFail, OrganizationInviteCancelResponse]
  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authTokenOpt: Option[String])(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationMembership]
  def declineInvitation(orgId: Id[Organization], userId: Id[User]): Seq[OrganizationInvite]
  def createGenericInvite(orgId: Id[Organization], inviterId: Id[User])(implicit eventContext: HeimdalContext): Either[OrganizationFail, OrganizationInvite] // creates a Universal Invite Link for an organization and inviter. Anyone with the link can join the Organization
  def getInvitesByOrganizationId(orgId: Id[Organization]): Set[OrganizationInvite]
  def getLastSentByOrganizationIdAndInviteeId(orgId: Id[Organization], inviteeId: Id[User]): Option[OrganizationInvite]
  def getInviteByOrganizationIdAndAuthToken(orgId: Id[Organization], authToken: String): Option[OrganizationInvite]
  def suggestMembers(userId: Id[User], orgId: Id[Organization], query: Option[String], limit: Int, request: UserRequest[_]): Future[Seq[MaybeOrganizationMember]]
  def isAuthValid(orgId: Id[Organization], authToken: String): Boolean
  def getViewerInviteInfo(orgId: Id[Organization], viewerIdOpt: Option[Id[User]], authTokenOpt: Option[String]): Option[OrganizationInviteInfo]
  def getInvitesByInviteeAndDecision(userId: Id[User], decision: InvitationDecision): Set[OrganizationInvite]
  def sendOrganizationInviteViaSlack(username: SlackUsername, orgId: Id[Organization], userIdOpt: Option[Id[User]]): Future[Unit]
  def sendInviteReminders(durationOfNoResponse: ReadableDuration, maxRemindersSent: Int): Future[Seq[(Id[Organization], Int)]]
  def sendInviteReminder(orgInvite: OrganizationInvite): Future[Option[ElectronicMail]]
}

@Singleton
class OrganizationInviteCommanderImpl @Inject() (db: Database,
    airbrake: AirbrakeNotifier,
    organizationRepo: OrganizationRepo,
    permissionCommander: PermissionCommander,
    organizationMembershipCommander: OrganizationMembershipCommander,
    organizationMembershipRepo: OrganizationMembershipRepo,
    organizationInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo,
    abookClient: ABookServiceClient,
    searchClient: SearchServiceClient,
    implicit val defaultContext: ExecutionContext,
    userRepo: UserRepo,
    elizaClient: ElizaServiceClient,
    userEmailAddressRepo: UserEmailAddressRepo,
    emailTemplateSender: EmailTemplateSender,
    kifiInstallationCommander: KifiInstallationCommander,
    organizationAnalytics: OrganizationAnalytics,
    userExperimentRepo: UserExperimentRepo,
    userCommander: Provider[UserCommander],
    slackTeamRepo: SlackTeamRepo,
    slackTeamMembershipRepo: SlackTeamMembershipRepo,
    slackChannelRepo: SlackChannelRepo,
    slackClient: SlackClientWrapper,
    pathCommander: PathCommander,
    orgExperimentRepo: OrganizationExperimentRepo,
    clock: Clock,
    implicit val publicIdConfig: PublicIdConfiguration) extends OrganizationInviteCommander with Logging {

  private def getValidationError(request: OrganizationInviteRequest)(implicit session: RSession): Option[OrganizationFail] = {
    val validateRequester = if (!permissionCommander.getOrganizationPermissions(request.orgId, Some(request.requesterId)).contains(OrganizationPermission.INVITE_MEMBERS)) {
      Some(OrganizationFail.INSUFFICIENT_PERMISSIONS)
    } else None

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
            val existingInvites = organizationInviteRepo.getAllByOrgId(request.orgId)
            val doInvitesExist = targetEmails.forall(email => existingInvites.exists(_.emailAddress.contains(email))) &&
              targetUsers.forall(userId => existingInvites.exists(_.userId.contains(userId)))

            if (!doInvitesExist) Some(OrganizationFail.INVITATION_NOT_FOUND)
            else None
          }
        }
    }
  }

  def inviteToOrganization(orgInvite: OrganizationInviteSendRequest)(implicit eventContext: HeimdalContext): Future[Either[OrganizationFail, Set[Either[BasicUser, RichContact]]]] = {
    val OrganizationInviteSendRequest(orgId, inviterId, inviteeAddresses, inviteeUserIds, message) = orgInvite

    val failOpt = db.readOnlyReplica { implicit session => getValidationError(orgInvite) }

    failOpt match {
      case Some(fail) => Future.successful(Left(fail))
      case _ => {
        val contactsByEmailAddressFut: Future[Map[EmailAddress, RichContact]] = {
          abookClient.internKifiContacts(inviterId, inviteeAddresses.map(BasicContact(_)).toSeq: _*).imap { kifiContacts =>
            (inviteeAddresses zip kifiContacts).toMap
          }
        }

        val userIdsByEmail = db.readOnlyReplica { implicit session => userEmailAddressRepo.getUsersByAddresses(inviteeAddresses) }

        contactsByEmailAddressFut map { contactsByEmail =>
          val (allInviteeUserIds, nonUserContactsByEmail) = contactsByEmail.foldLeft((inviteeUserIds, Map.empty[EmailAddress, RichContact])) {
            case ((userIds, nonUserContactsByEmail), (email, contact)) if contact.userId.isDefined || userIdsByEmail.contains(email) => (userIds + userIdsByEmail.getOrElse(email, contact.userId.get), nonUserContactsByEmail)
            case ((userIds, nonUserContactsByEmail), (email, contact)) => (userIds, nonUserContactsByEmail + (email -> contact))
          }

          val (nonMemberUserIds, basicUserByUserId) = db.readOnlyMaster { implicit session =>
            val nonMemberUserIds = allInviteeUserIds.filterNot(userId => organizationMembershipRepo.getAllByOrgId(orgId).exists(_.userId == userId))
            val basicUserById = basicUserRepo.loadAll(nonMemberUserIds)
            (nonMemberUserIds, basicUserById)
          }

          val addMembers = nonMemberUserIds.map { userId =>
            val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, userId = Some(userId), message = message)
            val inviteeInfo: Either[BasicUser, RichContact] = Left(basicUserByUserId(userId))
            (orgInvite, inviteeInfo)
          }
          val addByEmail = nonUserContactsByEmail.keys.map { emailAddress =>
            val orgInvite = OrganizationInvite(organizationId = orgId, inviterId = inviterId, emailAddress = Some(emailAddress), message = message)
            val inviteeInfo: Either[BasicUser, RichContact] = Right(nonUserContactsByEmail(emailAddress))
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
          val persistedInvites = invites.flatMap(persistInvitation(_))

          sendInvitationEmailsAndNotifications(persistedInvites, org, owner, inviter)
          organizationAnalytics.trackSentOrganizationInvites(inviterId, org, persistedInvites)

          Right(inviteeInfos)
        }
      }
    }
  }

  // return whether the invitation was persisted or not.
  private def persistInvitation(invite: OrganizationInvite, force: Boolean = false): Option[OrganizationInvite] = {
    val OrganizationInvite(_, _, _, _, _, orgId, inviterId, recipientId, recipientEmail, _, _, _, _, _) = invite
    val shouldInsert = force || db.readOnlyMaster { implicit s =>
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
    getInviteRecipient(invite) map { toRecipient =>
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

  def getInviteRecipient(invite: OrganizationInvite): Option[Either[Id[User], EmailAddress]] = {
    if (invite.userId.isDefined) Some(Left(invite.userId.get))
    else if (invite.emailAddress.isDefined) Some(Right(invite.emailAddress.get))
    else None
  }

  def notifyInviteeAboutInvitationToJoinOrganization(org: Organization, orgOwner: BasicUser, inviter: User, invitees: Set[Id[User]]) {
    invitees.foreach { inviteeId =>
      elizaClient.sendNotificationEvent(OrgNewInvite(
        Recipient.fromUser(inviteeId),
        currentDateTime,
        inviter.id.get,
        org.id.get
      ))
      val basicInvitee = db.readOnlyReplica { implicit s => basicUserRepo.load(inviteeId) }
      val request = OrgPushNotificationRequest(
        userId = inviteeId,
        message = s"${basicInvitee.fullName}, please join our ${org.name} team on Kifi!",
        pushNotificationExperiment = PushNotificationExperiment.Experiment1,
        category = OrgPushNotificationCategory.OrganizationInvitation
      )
      elizaClient.sendOrgPushNotification(request)
    }
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
        val existingInvites = organizationInviteRepo.getAllByOrgId(request.orgId)
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

  def acceptInvitation(orgId: Id[Organization], userId: Id[User], authTokenOpt: Option[String])(implicit context: HeimdalContext): Either[OrganizationFail, OrganizationMembership] = {
    val (invitations, membershipOpt) = db.readOnlyReplica { implicit session =>
      val userInvitations = organizationInviteRepo.getByOrgAndUserId(orgId, userId)
      val existingMembership = organizationMembershipRepo.getByOrgIdAndUserId(orgId, userId)
      val universalInvitation = authTokenOpt.flatMap(organizationInviteRepo.getByOrgIdAndAuthToken(orgId, _))
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

        val firstSuccess = addRequests.toStream.map(organizationMembershipCommander.addMembership).find(_.isRight)
        val invitationOpt = firstSuccess.map(_.right.map(_.membership))
        invitationOpt.getOrElse(Left(OrganizationFail.NO_VALID_INVITATIONS))
    }
    updatedMembership.right.map { success =>
      // on success accept invitations
      db.readWrite { implicit s =>
        // Notify inviters on organization joined.
        val organization = organizationRepo.get(orgId)
        val basicInvitee = userRepo.get(userId)
        val invitesWithActiveInviter = invitations.filter(invite => organizationMembershipRepo.getByOrgIdAndUserId(orgId, invite.inviterId).isDefined)

        SafeFuture {
          notifyInviterOnOrganizationInvitationAcceptance(invitesWithActiveInviter, basicInvitee, organization)
        }

        invitations.foreach { invite =>
          val acceptedInvite = if (!invite.isAnonymous) invite else invite.copy(id = None, userId = Some(userId))
          organizationInviteRepo.save(acceptedInvite.accepted.withState(OrganizationInviteStates.INACTIVE))
          if (authTokenOpt.exists(_.nonEmpty)) organizationAnalytics.trackAcceptedEmailInvite(organization, acceptedInvite.inviterId, acceptedInvite.userId, acceptedInvite.emailAddress)
        }
      }
      success
    }
  }

  def notifyInviterOnOrganizationInvitationAcceptance(invitesToAlert: Seq[OrganizationInvite], invitee: User, org: Organization): Unit = {
    invitesToAlert foreach { invite =>
      val title = s"${invitee.firstName} accepted your invitation to join ${org.abbreviatedName}!"
      val inviterId = invite.inviterId

      invite.userId.foreach { inviteeId =>
        elizaClient.sendNotificationEvent(
          OrgInviteAccepted(
            recipient = Recipient.fromUser(inviterId),
            time = currentDateTime,
            accepterId = inviteeId,
            invite.organizationId)
        )
      }

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
      if (!permissionCommander.getOrganizationPermissions(orgId, Some(inviterId)).contains(OrganizationPermission.INVITE_MEMBERS)) {
        Left(OrganizationFail.INSUFFICIENT_PERMISSIONS)
      } else {
        val invite = organizationInviteRepo.save(OrganizationInvite(organizationId = orgId, inviterId = inviterId))
        organizationAnalytics.trackSentOrganizationInvites(inviterId, organizationRepo.get(orgId), Set(invite))
        Right(invite)
      }
    }
  }

  def getInvitesByOrganizationId(orgId: Id[Organization]): Set[OrganizationInvite] = {
    db.readOnlyReplica { implicit session =>
      organizationInviteRepo.getAllByOrgId(orgId)
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

  def suggestMembers(userId: Id[User], orgId: Id[Organization], query: Option[String], limit: Int, request: UserRequest[_]): Future[Seq[MaybeOrganizationMember]] = {
    val recoScoreThreshold = .001d
    val usersAndEmailsFut = query.map(_.trim).filter(_.nonEmpty) match {
      case None =>
        abookClient.getRecommendationsForOrg(orgId, Some(userId), offset = 0, limit = limit + 9).map { orgInviteRecos =>
          val userRecos = orgInviteRecos.collect { case reco if reco.identifier.isLeft && reco.score >= recoScoreThreshold => reco.identifier.left.get }
          val emailRecos = orgInviteRecos.collect { case reco if reco.identifier.isRight && reco.score >= recoScoreThreshold => RichContact(email = reco.identifier.right.get, name = reco.name) }
          (userRecos, emailRecos)
        }
      case Some(validQuery) =>
        val memberCount = db.readOnlyMaster { implicit session => organizationMembershipRepo.countByOrgId(orgId) }
        val usersFut = searchClient.searchUsersByName(userId, validQuery, limit + memberCount, request.acceptLanguages.map(_.toString), request.experiments)
        val emailsFut = abookClient.prefixQuery(userId, validQuery, maxHits = Some(limit + 9))
        for {
          users <- usersFut
          emails <- emailsFut
        } yield {
          (users.collect { case hit if hit.userId != userId => hit.userId }, emails.collect { case hit if !hit.info.userId.contains(userId) => hit.info })
        }
    }

    usersAndEmailsFut.map {
      case (users, contacts) =>
        val nonMembers = db.readOnlyMaster { implicit session =>
          val members = organizationMembershipRepo.getByOrgIdAndUserIds(orgId, users.toSet)
          val fakes = userCommander.get().getAllFakeUsers()
          val isRequesterAdmin = userExperimentRepo.hasExperiment(userId, UserExperimentType.ADMIN)
          users.filter(id => !members.exists(_.userId == id) && (!fakes.contains(id) || isRequesterAdmin))
        }

        val uniqueEmailsWithName = contacts.groupBy(_.email.address.toLowerCase).map {
          case (address, groupedContacts) => (address, groupedContacts.find(_.name.isDefined).getOrElse(groupedContacts.head))
        }.values.toSeq

        val basicUserById = db.readOnlyReplica { implicit session => basicUserRepo.loadAllActive(nonMembers.toSet) }

        val suggestedUsers = nonMembers.collect {
          case uid if basicUserById.get(uid).isDefined =>
            MaybeOrganizationMember(Left(basicUserById(uid)), OrganizationRole.MEMBER, lastInvitedAt = None)
        }

        val suggestedEmailAddresses = uniqueEmailsWithName.map { contact =>
          MaybeOrganizationMember(Right(BasicContact.fromRichContact(contact)), OrganizationRole.MEMBER, lastInvitedAt = None)
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

  def getInvitesByInviteeAndDecision(userId: Id[User], decision: InvitationDecision): Set[OrganizationInvite] = {
    db.readOnlyReplica { implicit session => organizationInviteRepo.getByInviteeIdAndDecision(userId, decision) }
  }

  def sendOrganizationInviteViaSlack(username: SlackUsername, orgId: Id[Organization], userIdOpt: Option[Id[User]]): Future[Unit] = {
    import DescriptionElements._
    val (org, slackTeamIdOpt, slackUserIdOpt) = db.readOnlyMaster { implicit session =>
      val org = organizationRepo.get(orgId)
      val slackTeamIdOpt = slackTeamRepo.getByOrganizationId(orgId).map(_.slackTeamId)
      val slackUserIdOpt = slackTeamIdOpt.flatMap(slackTeamMembershipRepo.getBySlackTeamAndUsername(_, username).map(_.slackUserId))
      (org, slackTeamIdOpt, slackUserIdOpt)
    }
    slackTeamIdOpt match {
      case None => Future.failed(SlackActionFail.OrgNotConnected(orgId))
      case Some(slackTeamId) =>
        val futureSlackUserIdOpt = slackUserIdOpt.map(id => Future.successful(Some(id))) getOrElse slackClient.getUsers(slackTeamId).imap { users =>
          users.collectFirst { case user if user.name equalsIgnoreCase username => user.id }
        }
        futureSlackUserIdOpt.flatMap {
          case None => Future.failed(SlackFail.SlackUserNotFound(username, slackTeamId))
          case Some(slackUserId) =>
            val invite: OrganizationInvite = OrganizationInvite(organizationId = orgId, inviterId = org.ownerId, userId = None, emailAddress = None)
            val message: SlackMessageRequest = SlackMessageRequest.fromKifi {
              DescriptionElements.formatForSlack(DescriptionElements(SlackEmoji.mailboxWithMail, s" Here's the invitation access link you just requested!",
                "Click here to access this team on Kifi!" --> LinkElement(s"${pathCommander.pathForOrganization(org).absolute}?authToken=${invite.authToken}"),
                "\n", "...now back to watching ”The Big Bot Theory”"))
            }
            persistInvitation(invite, force = true).getOrElse(throw new Exception(s"can't create org (${orgId.id}) invite for ${slackUserId.value} on slack team ${slackTeamId.value}"))
            log.info(s"[OrgDMInvite] sending to slack team ${slackTeamId.value}, user ${slackUserId.value}")
            slackClient.sendToSlackHoweverPossible(slackTeamId, slackUserId.asChannel, message).map(_ => ())
        }
    }
  }

  def sendInviteReminders(durationOfNoResponse: ReadableDuration, maxRemindersSent: Int): Future[Seq[(Id[Organization], Int)]] = {
    // do not send more than 1 email in parallel
    val lock: ReactiveLock = new ReactiveLock
    val maxLastReminderSentAt = clock.now().minus(durationOfNoResponse)

    db.readOnlyReplica { implicit session =>
      val orgsWithPendingInvites = organizationInviteRepo.getDecisionCountsGroupedByOrganization(Set(InvitationDecision.PENDING), OrganizationInviteStates.ACTIVE)

      Future.sequence(orgsWithPendingInvites.map {
        case (orgId, _) =>
          if (orgExperimentRepo.hasExperiment(orgId, OrganizationExperimentType.ORG_INVITE_REMINDERS)) {
            organizationInviteRepo.getAllByLastReminderSentAt(orgId, maxLastReminderSentAt, maxRemindersSent).foldLeft(Future.successful(0)) {
              case (emailsSentF, invite) =>
                emailsSentF.flatMap { emailsSent =>
                  lock.withLockFuture { sendInviteReminder(invite).map(emailOpt => if (emailOpt.isDefined) emailsSent + 1 else emailsSent) }
                }
            }.map(emailsSent => orgId -> emailsSent)
          } else Future.successful(orgId -> 0)
      })
    }
  }

  def sendInviteReminder(invite: OrganizationInvite): Future[Option[ElectronicMail]] = {
    val fromUserId = invite.inviterId
    db.readWrite { implicit session =>
      organizationInviteRepo.save(invite.copy(remindersSent = invite.remindersSent + 1, lastReminderSentAt = Some(clock.now())))

      getInviteRecipient(invite).map { toRecipient =>
        val htmlTemplate = views.html.email.organizationInvitationReminderPlain(
          toRecipient.left.toOption, fromUserId, invite.message, invite.organizationId, invite.authToken)
        val auxiliaryData = new HeimdalContext(Map[String, ContextData](("reminder", ContextBoolean(true))))
        val emailToSend = EmailToSend(
          fromName = Some(Right("Kifi")),
          from = SystemEmailAddress.NOTIFICATIONS,
          subject = s"${fullName(fromUserId)}'s invitation to join ${organizationName(invite.organizationId)} on Kifi is waiting your response",
          to = toRecipient,
          category = toRecipient.fold(
            _ => NotificationCategory.User.ORGANIZATION_INVITATION_REMINDER,
            _ => NotificationCategory.NonUser.ORGANIZATION_INVITATION_REMINDER),
          htmlTemplate = htmlTemplate,
          textTemplate = Some(views.html.email.organizationInvitationReminderText(toRecipient.left.toOption, fromUserId)),
          templateOptions = Seq(CustomLayout).toMap,
          auxiliaryData = Some(auxiliaryData),
          campaign = Some("na"),
          channel = Some("vf_email"),
          source = Some("organization_invite_reminder")
        )
        emailTemplateSender.send(emailToSend).map(Some(_))
      } getOrElse {
        Future.successful(None)
      }
    }
  }
}
