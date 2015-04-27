package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.{ TwitterSocialGraph, TwitterSocialGraphImpl, LinkedInSocialGraph, BasicUserRepo }
import com.keepit.common.store.S3ImageStore
import com.keepit.controllers.website.routes
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.social.{ SocialNetworkType, SocialNetworks }
import com.keepit.common.core._

import java.net.URLEncoder

import com.ning.http.client.providers.netty.NettyResponse
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Await, Future }
import scala.concurrent.duration._
import scala.util.Try
import com.keepit.common.queue.{ CancelInvitation }
import com.keepit.abook.ABookServiceClient

import akka.actor.Scheduler
import com.keepit.model.SocialConnection
import play.api.libs.json.JsString
import com.keepit.inject.FortyTwoConfig
import com.keepit.social.SecureSocialClientIds
import com.keepit.common.mail.EmailAddress
import com.keepit.social.SocialId
import com.keepit.commanders.emails.{ EmailSenderProvider, EmailOptOutCommander }
import com.keepit.abook.model.{ InviteRecommendation, RichContact }

case class FullSocialId(network: SocialNetworkType, identifier: Either[SocialId, EmailAddress], name: Option[String] = None) {
  override def toString(): String = s"${network.name}/${identifier.left.map(_.id).right.map(_.address).merge}"
}

object FullSocialId {
  def apply(fullSocialId: String): FullSocialId = {
    val Array(networkName, idString) = fullSocialId.split("/")
    val network = SocialNetworkType(networkName)
    val (identifier, name) = network match {
      case SocialNetworks.EMAIL => {
        val contact = BasicContact.fromString(idString).get
        (Right(contact.email), contact.name)
      }
      case _ => (Left(SocialId(idString)), None)
    }
    FullSocialId(network, identifier, name)
  }
  implicit val format: Format[FullSocialId] = Format(Reads.of[String].map(FullSocialId.apply), Writes(fullSocialId => JsString(fullSocialId.toString())))
}

case class InviteInfo(invitation: Invitation, friend: Either[SocialUserInfo, RichContact], subject: Option[String], message: Option[String])

case class InviteStatus(sent: Boolean, savedInvite: Option[Invitation], code: String)

object InviteStatus {
  def sent(savedInvite: Invitation) = {
    require(savedInvite.state == InvitationStates.ACTIVE, "Sent invite was not saved with an active state")
    InviteStatus(true, Some(savedInvite), "invite_sent")
  }
  def clientHandle(savedInvite: Invitation) = InviteStatus(false, Some(savedInvite), "client_handle")
  def facebookError(code: Int, savedInvite: Option[Invitation]) = InviteStatus(false, savedInvite, s"facebook_error_$code")
  def linkedInError(code: Int) = InviteStatus(false, None, s"linkedin_error_{$code}")
  def twitterError(code: Int) = InviteStatus(false, None, s"twitter_error_$code")
  val unknownError = InviteStatus(false, None, "unknown_error")
  val forbidden = InviteStatus(false, None, "over_invite_limit")
  val notFound = InviteStatus(false, None, "invite_not_found")
  val unsupportedNetwork = InviteStatus(false, None, "unsupported_social_network")
}

case class FailedInvitationException(inviteStatus: InviteStatus, inviteId: Option[ExternalId[Invitation]], userId: Option[Id[User]], friendFullSocialId: Option[FullSocialId])
  extends Exception(s"Invitation $inviteId from user $userId to $friendFullSocialId was not processed correctly: ${inviteStatus}")

class InviteCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    fortytwoConfig: FortyTwoConfig,
    secureSocialClientIds: SecureSocialClientIds,
    userRepo: UserRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    userConnectionRepo: UserConnectionRepo,
    invitationRepo: InvitationRepo,
    val userActionsHelper: UserActionsHelper,
    postOffice: LocalPostOffice,
    emailAddressRepo: UserEmailAddressRepo,
    socialConnectionRepo: SocialConnectionRepo,
    eliza: ElizaServiceClient,
    basicUserRepo: BasicUserRepo,
    linkedIn: LinkedInSocialGraph,
    twitter: TwitterSocialGraph,
    eventContextBuilder: HeimdalContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    abook: ABookServiceClient,
    clock: Clock,
    s3ImageStore: S3ImageStore,
    emailOptOutCommander: EmailOptOutCommander,
    shoeboxRichConnectionCommander: ShoeboxRichConnectionCommander,
    abookServiceClient: ABookServiceClient,
    emailSenderProvider: EmailSenderProvider,
    implicit val executionContext: ExecutionContext,
    scheduler: Scheduler) extends Logging {

  private lazy val baseUrl = fortytwoConfig.applicationBaseUrl

  def markPendingInvitesAsAccepted(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    val anyPendingInvites = getOrCreateInvitesForUser(userId, invId).filter {
      case (invite, _) =>
        Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(invite.state)
    }
    db.readWrite { implicit s =>
      val actuallyInvitedUser = anyPendingInvites.collectFirst {
        case (invite, _) if invite.senderUserId.isDefined =>
          val user = userRepo.get(userId)
          if (user.state == UserStates.PENDING) {
            userRepo.save(user.withState(UserStates.ACTIVE))
          } else user
      }

      for ((invite, originalSocialNetwork) <- anyPendingInvites) {
        if (actuallyInvitedUser.isDefined && invite.createdAt.isAfter(actuallyInvitedUser.get.createdAt)) {
          invitationRepo.save(invite.withState(InvitationStates.INACTIVE))
        } else {
          if (Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(invite.state)) {
            val acceptedInvite = invitationRepo.save(invite.withState(InvitationStates.ACCEPTED))
            connectInvitedUsers(userId, invite)
            reportReceivedInvitation(userId, originalSocialNetwork, acceptedInvite, invId == Some(invite.externalId))
          }
        }
      }
    }
  }

  private def getOrCreateInvitesForUser(userId: Id[User], cookieInviteId: Option[ExternalId[Invitation]]): Set[(Invitation, SocialNetworkType)] = {
    db.readWrite { implicit session =>
      val userSocialAccounts = socialUserInfoRepo.getByUser(userId)
      val fortyTwoSocialAccount = userSocialAccounts.find(_.networkType == SocialNetworks.FORTYTWO).get
      val userEmailAccounts = emailAddressRepo.getAllByUser(userId)
      val cookieInvite = cookieInviteId.flatMap { inviteExtId =>
        Try(invitationRepo.get(inviteExtId)).toOption.collect {
          case socialInvite if socialInvite.recipientSocialUserId.isDefined =>
            val invitedSocialUserId = socialInvite.recipientSocialUserId.get
            userSocialAccounts.find(_.id.get == invitedSocialUserId) match {
              case Some(invitedSocialAccount) =>
                socialInvite -> invitedSocialAccount.networkType
              case None =>
                socialInvite.senderUserId.foreach { senderId =>
                  session.onTransactionSuccess { shoeboxRichConnectionCommander.processUpdate(CancelInvitation(senderId, Some(invitedSocialUserId), None)) }
                }
                val invitedSocialAccount = socialUserInfoRepo.get(invitedSocialUserId)
                val fortyTwoInvite = invitationRepo.save(socialInvite.withRecipientSocialUserId(fortyTwoSocialAccount.id))
                fortyTwoInvite -> invitedSocialAccount.networkType
            }
          case emailInvite if emailInvite.recipientEmailAddress.isDefined =>
            val invitedEmailAddress = emailInvite.recipientEmailAddress.get
            userEmailAccounts.find(_.address == invitedEmailAddress) match {
              case Some(invitedEmailAccount) =>
                emailInvite -> SocialNetworks.EMAIL
              case None => {
                emailInvite.senderUserId.foreach { senderId =>
                  session.onTransactionSuccess { shoeboxRichConnectionCommander.processUpdate(CancelInvitation(senderId, None, Some(invitedEmailAddress))) }
                }
                val fortyTwoInvite = invitationRepo.save(emailInvite.copy(recipientSocialUserId = fortyTwoSocialAccount.id, recipientEmailAddress = None))
                fortyTwoInvite -> SocialNetworks.EMAIL
              }
            }
        }
      }

      val networkTypes = userSocialAccounts.map { su => su.id.get -> su.networkType }.toMap
      val verifiedEmails = userEmailAccounts.filter(_.verified)
      val otherInvites = invitationRepo.getByRecipientSocialUserIdsAndEmailAddresses(userSocialAccounts.map(_.id.get).toSet, verifiedEmails.map(_.address).toSet)

      val (invitesWithSocialUserId, invitesWithoutSocialUserId) = otherInvites.partition(_.recipientSocialUserId.isDefined)
      val otherSocialInvites = invitesWithSocialUserId.map(invite => (invite, networkTypes(invite.recipientSocialUserId.get)))
      val otherEmailInvites = invitesWithoutSocialUserId.collect { case invite if invite.recipientEmailAddress.isDefined => (invite, SocialNetworks.EMAIL) }
      val existingInvites = otherSocialInvites.toSet ++ otherEmailInvites ++ cookieInvite

      if (existingInvites.isEmpty) {
        val fakeFortyTwoInvite = invitationRepo.save(Invitation(
          createdAt = clock.now,
          senderUserId = None,
          recipientSocialUserId = fortyTwoSocialAccount.id,
          state = InvitationStates.ACTIVE
        ))
        Set(fakeFortyTwoInvite -> SocialNetworks.FORTYTWO)
      } else {
        existingInvites
      }
    }
  }

  private def connectInvitedUsers(userId: Id[User], invite: Invitation)(implicit session: RWSession) = {
    invite.senderUserId.map { senderUserId =>
      val newFortyTwoSocialUser = socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.FORTYTWO)
      val inviterFortyTwoSocialUser = socialUserInfoRepo.getByUser(senderUserId).find(_.networkType == SocialNetworks.FORTYTWO)
      for {
        su1 <- newFortyTwoSocialUser
        su2 <- inviterFortyTwoSocialUser
      } yield {
        socialConnectionRepo.getConnectionOpt(su1.id.get, su2.id.get) match {
          case Some(sc) =>
            socialConnectionRepo.save(sc.withState(SocialConnectionStates.ACTIVE))
          case None =>
            socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su2.id.get, state = SocialConnectionStates.ACTIVE))
        }
      }
      notifyClientsOfConnection(userId, senderUserId)
      emailClientsOfConnection(userId, senderUserId)
      userConnectionRepo.addConnections(userId, Set(senderUserId), requested = true)
    }
  }

  private def emailClientsOfConnection(user1Id: Id[User], user2Id: Id[User]) = {
    emailSenderProvider.connectionMade(user1Id, user2Id, NotificationCategory.User.CONNECTION_MADE)
    emailSenderProvider.connectionMade(user2Id, user1Id, NotificationCategory.User.CONNECTION_MADE)
  }

  private def notifyClientsOfConnection(user1Id: Id[User], user2Id: Id[User]) = {
    delay {
      val (user1, user2) = db.readOnlyReplica { implicit session => basicUserRepo.load(user1Id) -> basicUserRepo.load(user2Id) }
      eliza.sendToUser(user1Id, Json.arr("new_friends", Set(user2)))
      eliza.sendToUser(user2Id, Json.arr("new_friends", Set(user1)))
    }
  }

  private def delay(f: => Unit) = {
    import scala.concurrent.duration._
    scheduler.scheduleOnce(5 minutes) {
      f
    }
  }

  def invite(request: UserRequest[_], userId: Id[User], fullSocialId: FullSocialId, subject: Option[String], message: Option[String], source: String): Future[InviteStatus] = {
    getInviteInfo(userId, fullSocialId, subject: Option[String], message: Option[String]).flatMap {
      case inviteInfo if isAllowed(inviteInfo) => processInvite(request, inviteInfo, source)
      case _ => Future.successful(InviteStatus.forbidden)
    }
  }

  private def processInvite(request: UserRequest[_], inviteInfo: InviteInfo, source: String): Future[InviteStatus] = {
    log.info(s"[processInvite] Processing: $inviteInfo")
    val socialNetwork = inviteInfo.friend.left.toOption.map(_.networkType) getOrElse SocialNetworks.EMAIL
    val inviteStatusFuture = socialNetwork match {
      case SocialNetworks.FACEBOOK => Future.successful(handleFacebookInvite(inviteInfo))
      case SocialNetworks.LINKEDIN => sendInvitationForLinkedIn(inviteInfo)
      case SocialNetworks.TWITTER => sendTwitterDM(inviteInfo)
      case SocialNetworks.EMAIL => Future.successful(sendEmailInvitation(inviteInfo))
      case _ => Future.successful(InviteStatus.unsupportedNetwork)
    }

    inviteStatusFuture imap {
      case inviteStatus =>
        log.info(s"[processInvite] Processed: $inviteStatus")
        if (inviteStatus.sent) { reportSentInvitation(Some(request), inviteStatus.savedInvite.get, socialNetwork, source) }
        inviteStatus
    }
  }

  private def sendTwitterDM(inviteInfo: InviteInfo): Future[InviteStatus] = {
    val invite = inviteInfo.invitation
    val userId = invite.senderUserId.get
    val me = db.readOnlyReplica { implicit s => socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.TWITTER).get }
    val socialUserInfo = inviteInfo.friend.left.get
    val path = routes.InviteController.acceptInvite(invite.externalId).url
    val message = s"Join me on Kifi, the smartest way to collect, discover, and share knowledge. Try it free! $baseUrl$path"
    val receiverUserId = socialUserInfo.socialId.id.toLong
    twitter.sendDM(me, receiverUserId, message) map { resp =>
      if (resp.status != 200) {
        airbrake.notify(s"[sendTwitterDM] non-OK response. receiverUserId=$receiverUserId; status=${resp.status} body=${resp.body}; request=${resp.underlying[NettyResponse]} request.uri=${resp.underlying[NettyResponse].getUri}")
        log.error(s"[sendTwitterDM($userId)] Cannot send invitation ($invite): ${resp.body}")
        if (resp.status == Status.UNAUTHORIZED) {
          db.readWrite { implicit rw =>
            val latestSocialUserInfo = socialUserInfoRepo.get(socialUserInfo.id.get)
            socialUserInfoRepo.save(latestSocialUserInfo.copy(state = SocialUserInfoStates.APP_NOT_AUTHORIZED))
          }
        }
        InviteStatus.twitterError(resp.status)
      } else {
        log.info(s"[sendTwitterDM] response.json=${resp.json}")
        val savedInvite = db.readWrite { implicit rw => invitationRepo.save(invite.withState(InvitationStates.ACTIVE).withLastSentTime(clock.now())) }
        InviteStatus.sent(savedInvite)
      }
    }
  }

  private def sendEmailInvitation(inviteInfo: InviteInfo): InviteStatus = {
    val invite = inviteInfo.invitation
    val senderUserId = invite.senderUserId.get
    val invitee = inviteInfo.friend.right.get

    emailSenderProvider.kifiInvite.apply(invitee.email, invite.senderUserId.get, invite.externalId)
    db.readWrite { implicit session =>
      val savedInvite = invitationRepo.save(invite.withState(InvitationStates.ACTIVE).withLastSentTime(clock.now()))
      val basicContact = BasicContact(email = invitee.email)

      abookServiceClient.internKifiContacts(senderUserId, basicContact)
      InviteStatus.sent(savedInvite)
    }
  }

  private def sendInvitationForLinkedIn(inviteInfo: InviteInfo): Future[InviteStatus] = {
    val invite = inviteInfo.invitation
    val userId = invite.senderUserId.get
    val me = db.readOnlyReplica { implicit s => socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.LINKEDIN).get }
    val socialUserInfo = inviteInfo.friend.left.get
    val path = routes.InviteController.acceptInvite(invite.externalId).url
    val subject = inviteInfo.subject.getOrElse(s"${me.fullName.split(' ')(0)} has invited you to join Kifi!") // todo: same for email
    val message = inviteInfo.message.getOrElse(
      """
        |Kifi is a remarkable new way to collect, discover, and share knowledge. Powered by people, search, and recommendations. Instantly share with friends, and have conversations right on the page!
        |
        |Try it free: https://www.kifi.com
        |
        |Kifi is available for Chrome, Firefox, iOS, and Android.
        | """.stripMargin
    )
    val messageWithUrl = s"$message\n$baseUrl$path\n\nKifi is available for desktop only on chrome and firefox.\nSafari, Internet Explorer and mobile are coming soon!"
    log.info(s"[sendInvitationForLinkedIn($userId,${socialUserInfo.id})] subject=$subject message=$messageWithUrl")
    linkedIn.sendMessage(me, socialUserInfo, subject, messageWithUrl).map { resp =>
      db.readWrite(attempts = 2) { implicit session =>
        log.info(s"[sendInvitationForLinkedin($userId,${socialUserInfo.id})] resp=${resp.statusText} resp.body=${resp.body} cookies=${resp.cookies.mkString(",")} headers=${resp.underlying[NettyResponse].getHeaders.toString}")
        if (resp.status != Status.CREATED) { // per LinkedIn doc
          airbrake.notify(s"Failed to send LinkedIn invite for $userId; resp=${resp.statusText} resp.body=${resp.body} invite=$invite; socialUser=$socialUserInfo")
          log.error(s"[sendInvitationForLinkedIn($userId)] Cannot send invitation ($invite): ${resp.body}")
          if (resp.status == Status.UNAUTHORIZED) {
            val latestSocialUserInfo = socialUserInfoRepo.get(socialUserInfo.id.get)
            socialUserInfoRepo.save(latestSocialUserInfo.copy(state = SocialUserInfoStates.APP_NOT_AUTHORIZED))
          }
          InviteStatus.linkedInError(resp.status)
        } else {
          val saved = invitationRepo.save(invite.withState(InvitationStates.ACTIVE).withLastSentTime(clock.now()))
          log.info(s"[sendInvitationForLinkedin($userId,${socialUserInfo.id})] savedInvite=${saved}")
          InviteStatus.sent(saved)
        }
      }
    }
  }

  private def handleFacebookInvite(inviteInfo: InviteInfo): InviteStatus = {
    val invitation = inviteInfo.invitation
    val userId = inviteInfo.invitation.senderUserId.get
    val saved = db.readWrite(attempts = 2) { implicit s => invitationRepo.save(invitation) }
    log.info(s"[handleFacebookInvite(${userId}, ${invitation.recipientSocialUserId.get}})] Persisted ${invitation}")
    InviteStatus.clientHandle(saved)
  }

  def acceptUrl(invitationId: ExternalId[Invitation], encode: Boolean = true) = {
    val url = s"$baseUrl${routes.InviteController.acceptInvite(invitationId)}"
    if (encode) URLEncoder.encode(url, "UTF-8") else url
  }
  def fbConfirmUrl(invitationId: ExternalId[Invitation], source: String) = URLEncoder.encode(s"$baseUrl${routes.InviteController.confirmInvite(invitationId, source, None, None)}", "UTF-8")
  def fbInviteUrl(invitationId: ExternalId[Invitation], socialId: SocialId, source: String): String = {
    val link = acceptUrl(invitationId)
    val redirectUri = fbConfirmUrl(invitationId, source)
    s"https://www.facebook.com/dialog/send?app_id=${secureSocialClientIds.facebook}&link=$link&redirect_uri=$redirectUri&to=$socialId"
  }

  def fbTitle(inviterName: Option[String]): String = inviterName.map { name => s"$name has invited you to join Kifi!" } getOrElse "You've been invited to join Kifi!"
  val fbDescription: String = "Kifi is a remarkable new way to collect, discover, and share knowledge. Powered by people, search, and recommendations."

  def confirmFacebookInvite(request: Option[UserRequest[_]], id: ExternalId[Invitation], source: String, errorMsg: Option[String], errorCode: Option[Int]): InviteStatus = {
    val inviteStatus = db.readWrite { implicit session =>
      val existingInvitation = invitationRepo.getOpt(id)
      val inviteStatus = existingInvitation match {
        case Some(invite) if errorCode.isEmpty => InviteStatus.sent(invitationRepo.save(invite.copy(state = InvitationStates.ACTIVE).withLastSentTime(clock.now())))
        case _ => errorCode.map(InviteStatus.facebookError(_, existingInvitation)) getOrElse InviteStatus.notFound
      }
      inviteStatus
    }

    if (inviteStatus.sent) {
      val activeInvite = inviteStatus.savedInvite.get
      log.info(s"[confirmFacebookInvite(${id})] Confirmed ${inviteStatus}")
      reportSentInvitation(request, activeInvite, SocialNetworks.FACEBOOK, source)
    } else { log.error(s"[confirmFacebookInvite(${id}})] Failed to confirmed ${inviteStatus}") }
    inviteStatus
  }

  private class IllegalInvitationException(message: String) extends Exception(message)
  private def isAllowed(inviteInfo: InviteInfo): Boolean = {
    val allowed = inviteInfo.invitation.canBeSent
    if (!allowed) {
      log.error(s"[InviteCommander.isAllowed] Illegal invitation: $inviteInfo")
      airbrake.notify(new IllegalInvitationException(s"An invitation was rejected: $inviteInfo"))
    }
    allowed
  }

  private def getInviteInfo(userId: Id[User], fullSocialId: FullSocialId, subject: Option[String], message: Option[String]): Future[InviteInfo] = {
    val friendFuture = fullSocialId.identifier match {
      case Left(socialId) =>
        val friendSocialUserInfo = db.readOnlyReplica { implicit session => socialUserInfoRepo.get(socialId, fullSocialId.network) }
        Future.successful(Left(friendSocialUserInfo))
      case Right(emailAddress) => {
        val friendRichContactFuture = abook.internKifiContacts(userId, BasicContact(emailAddress, fullSocialId.name)).map { case Seq(richContact) => Right(richContact) }
        friendRichContactFuture
      }
    }

    friendFuture.map { friend =>
      val friendId = friend.left.map(_.id.get).right.map(_.email)
      val invitation = getInvitation(userId, friendId)
      InviteInfo(invitation, friend, subject, message)
    }
  }

  private def getInvitation(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress]): Invitation = {
    val existingInvitation = db.readOnlyMaster { implicit session =>
      friendId match {
        case Left(socialUserId) => invitationRepo.getBySenderIdAndRecipientSocialUserId(userId, socialUserId)
        case Right(emailAddress) => invitationRepo.getBySenderIdAndRecipientEmailAddress(userId, emailAddress)
      }
    }

    existingInvitation getOrElse Invitation(
      senderUserId = Some(userId),
      recipientSocialUserId = friendId.left.toOption,
      recipientEmailAddress = friendId.right.toOption,
      state = InvitationStates.INACTIVE
    )
  }

  private def reportSentInvitation(request: Option[UserRequest[_]], invite: Invitation, socialNetwork: SocialNetworkType, source: String): Unit = SafeFuture {
    invite.senderUserId.foreach { senderId =>
      val contextBuilder = eventContextBuilder()
      request.foreach(contextBuilder.addRequestInfo(_))
      contextBuilder += ("action", "sent")
      contextBuilder += ("socialNetwork", socialNetwork.toString)
      contextBuilder += ("inviteId", invite.externalId.id)
      contextBuilder += ("invitationNumber", invite.timesSent)
      contextBuilder += ("source", source)
      contextBuilder += ("category", "kifiInvitation")
      contextBuilder += ("numNonUserInvited", 1)
      invite.recipientEmailAddress.foreach { emailAddress => contextBuilder += ("recipientEmailAddress", emailAddress.toString) }
      invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }
      heimdal.trackEvent(UserEvent(senderId, contextBuilder.build, UserEventTypes.INVITED, invite.lastSentAt getOrElse invite.createdAt))

      // also send used_kifi event
      contextBuilder += ("action", "invited")
      heimdal.trackEvent(UserEvent(senderId, contextBuilder.build, UserEventTypes.USED_KIFI, invite.lastSentAt getOrElse invite.createdAt))
    }
  }

  private def reportReceivedInvitation(receiverId: Id[User], socialUserNetwork: SocialNetworkType, invite: Invitation, actuallyAccepted: Boolean): Unit = {
    invite.senderUserId.foreach { senderId =>
      SafeFuture {
        val invitedVia = socialUserNetwork match {
          case SocialNetworks.FORTYTWO => SocialNetworks.EMAIL
          case other => other
        }
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder += ("socialNetwork", invitedVia.toString)
        contextBuilder += ("inviteId", invite.externalId.id)
        invite.recipientEmailAddress.foreach { emailAddress => contextBuilder += ("recipientEmailAddress", emailAddress.toString) }
        invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }

        if (actuallyAccepted) {
          val acceptedAt = invite.updatedAt
          // Credit the sender of the accepted invite
          contextBuilder += ("action", "accepted")
          contextBuilder += ("recipientId", receiverId.toString)
          contextBuilder += ("category", "kifiInvitation")
          heimdal.trackEvent(UserEvent(senderId, contextBuilder.build, UserEventTypes.INVITED, acceptedAt))

          // Include "future" acceptance in past event
          contextBuilder.data.remove("recipientId")
          contextBuilder.data.remove("action")
          contextBuilder += ("toBeAccepted", acceptedAt)
          contextBuilder
        }

        // Backfill the history of the new user with all the invitations he/she received
        contextBuilder += ("action", "wasInvited")
        contextBuilder += ("senderId", senderId.id)
        heimdal.trackEvent(UserEvent(receiverId, contextBuilder.build, UserEventTypes.JOINED, invite.lastSentAt getOrElse invite.createdAt))
      }
    }
  }

  def getInviteRecommendations(userId: Id[User], page: Int, pageSize: Int): Future[Seq[InviteRecommendation]] = {
    abook.getRipestFruits(userId, page, pageSize).map { ripestFruits =>
      val (lastInvitedAtByEmailAddress, lastInvitedAtBySocialUserId, socialUserInfosBySocialUserId) = {
        val (emailConnections, socialConnections) = (ripestFruits.partition(_.connectionType == SocialNetworks.EMAIL))
        val emailAddresses = emailConnections.flatMap(_.friendEmailAddress)
        val socialUserIds = socialConnections.flatMap(_.friendSocialId)
        db.readOnlyReplica { implicit session =>
          (
            invitationRepo.getLastInvitedAtBySenderIdAndRecipientEmailAddresses(userId, emailAddresses),
            invitationRepo.getLastInvitedAtBySenderIdAndRecipientSocialUserIds(userId, socialUserIds),
            socialUserInfoRepo.getSocialUserBasicInfos(socialUserIds)
          )
        }
      }
      ripestFruits.map { richConnection =>
        richConnection.connectionType match {
          case SocialNetworks.EMAIL =>
            val emailAddress = richConnection.friendEmailAddress.get
            InviteRecommendation(SocialNetworks.EMAIL, Left(emailAddress), richConnection.friendName, None, lastInvitedAtByEmailAddress.get(emailAddress), -1)
          case socialNetwork =>
            val socialUserId = richConnection.friendSocialId.get
            val socialUserInfo = socialUserInfosBySocialUserId(socialUserId)
            val name = richConnection.friendName getOrElse socialUserInfo.fullName
            val pictureUrl = socialUserInfo.getPictureUrl(80, 80)
            InviteRecommendation(socialNetwork, Right(socialUserInfo.socialId), Some(name), pictureUrl, lastInvitedAtBySocialUserId.get(socialUserId), -1)
        }
      }
    }
  }
}
