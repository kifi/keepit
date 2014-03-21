package com.keepit.commanders

import com.google.inject.Inject

import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.social.{LinkedInSocialGraph, BasicUserRepo}
import com.keepit.common.store.S3ImageStore
import com.keepit.controllers.website.routes
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal._
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{EContact, EmailAddressRepo, Invitation, InvitationRepo, InvitationStates, NotificationCategory}
import com.keepit.model.{SocialConnection, SocialConnectionRepo, SocialConnectionStates, SocialUserInfo, SocialUserInfoRepo}
import com.keepit.model.{User, UserConnectionRepo, UserRepo, UserStates, UserValues, UserValueRepo}
import com.keepit.social.{SecureSocialClientIds, SocialId, SocialNetworkType, SocialNetworks}

import java.net.URLEncoder

import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.util.Try
import com.keepit.common.queue.{RecordInvitation, CancelInvitation}
import com.keepit.abook.ABookServiceClient

case class FullSocialId(network: SocialNetworkType, identifier: Either[SocialId, String])

object FullSocialId {
  def fromString(fullSocialId: String): Option[FullSocialId] = Try {
    val Array(networkName, idString) = fullSocialId.split("/")
    val network = SocialNetworkType(networkName)
    val identifier = network match {
      case SocialNetworks.EMAIL => Right(idString)
      case _ => Left(SocialId(idString))
    }
    new FullSocialId(network, identifier)
  }.toOption

  def toString(fullSocialId: FullSocialId): String =s"${fullSocialId.network.name}/${fullSocialId.identifier.left.map(_.id).merge}"
}

case class InviteInfo(userId: Id[User], friend: Either[SocialUserInfo, EContact], invitationNumber: Int, subject: Option[String], message: Option[String], source: String)

case class InviteStatus(sent: Boolean, savedInvite: Option[Invitation], code: String)

object InviteStatus {
  def sent(savedInvite: Invitation) = {
    require(savedInvite.state == InvitationStates.ACTIVE, "Sent invite was not saved with an active state")
    InviteStatus(true, Some(savedInvite), "invite_sent")
  }
  def clientHandle(savedInvite: Invitation) = InviteStatus(false, Some(savedInvite), "client_handle")
  def facebookError(code: Int) = InviteStatus(false, None, s"facebook_error_$code")
  val unknownError = InviteStatus(false, None, "unknown_error")
  val forbidden = InviteStatus(false, None, "over_invite_limit")
  val notFound = InviteStatus(false, None, "invite_not_found")
  val unsupportedNetwork = InviteStatus(false, None, "unsupported_social_network")
}

class InviteCommander @Inject() (
  db: Database,
  airbrake: AirbrakeNotifier,
  fortytwoConfig: FortyTwoConfig,
  secureSocialClientIds: SecureSocialClientIds,
  userRepo: UserRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  userConnectionRepo: UserConnectionRepo,
  invitationRepo: InvitationRepo,
  actionAuthenticator: ActionAuthenticator,
  postOffice: LocalPostOffice,
  emailAddressRepo: EmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  eliza: ElizaServiceClient,
  basicUserRepo: BasicUserRepo,
  userValueRepo: UserValueRepo,
  linkedIn: LinkedInSocialGraph,
  eventContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  abook: ABookServiceClient,
  clock: Clock,
  s3ImageStore: S3ImageStore,
  emailOptOutCommander: EmailOptOutCommander,
  shoeboxRichConnectionCommander: ShoeboxRichConnectionCommander) extends Logging {

  private lazy val baseUrl = fortytwoConfig.applicationBaseUrl

  def markPendingInvitesAsAccepted(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    val anyPendingInvites = getOrCreateInvitesForUser(userId, invId).filter { case (_, in) =>
      Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(in.state)
    }
    db.readWrite { implicit s =>
      anyPendingInvites.find(_._2.senderUserId.isDefined).map { case (_, invite) =>
        val user = userRepo.get(userId)
        if (user.state == UserStates.PENDING) {
          userRepo.save(user.withState(UserStates.ACTIVE))
        }
      }
      for ((su, invite) <- anyPendingInvites) {
        connectInvitedUsers(userId, invite)
        if (Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(invite.state)) {
          val acceptedInvite = invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
          reportReceivedInvitation(userId, su.networkType, acceptedInvite, invId == Some(invite.externalId))
        }
      }
    }
  }

  private def getOrCreateInvitesForUser(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    db.readWrite { implicit session =>
      val userSocialAccounts = socialUserInfoRepo.getByUser(userId)
      val fortyTwoSocialAccount = userSocialAccounts.find(_.networkType == SocialNetworks.FORTYTWO).get
      val cookieInvite = invId.flatMap { inviteExtId =>
        Try(invitationRepo.get(inviteExtId)).toOption.map { invite =>
          userSocialAccounts.find(_.id == invite.recipientSocialUserId) match {
            case Some(invitedSocialAccount) => invitedSocialAccount -> invite
            case None =>
              // User signed up using a different social account than the one he was invited with.
              invite.senderUserId.foreach { senderId =>
               session.onTransactionSuccess {
                  invite.recipientSocialUserId.foreach { recipientSocialUserId =>
                    shoeboxRichConnectionCommander.processUpdate(CancelInvitation(senderId, Some(recipientSocialUserId), None))
                  }
                  invite.recipientEContactId.foreach { recipientEContactId =>
                    shoeboxRichConnectionCommander.processUpdate(CancelInvitation(senderId, None, Some(recipientEContactId)))
                  }
               }
              }
              fortyTwoSocialAccount -> invitationRepo.save(invite.copy(recipientSocialUserId = fortyTwoSocialAccount.id, recipientEContactId = None))
          }
        }
      }

      val otherInvites = for {
        su <- userSocialAccounts
        invite <- invitationRepo.getByRecipientSocialUserId(su.id.get)
      } yield (su, invite)

      val existingInvites = otherInvites.toSet ++ cookieInvite.toSet

      if (existingInvites.isEmpty) {
        Set(fortyTwoSocialAccount -> invitationRepo.save(Invitation(
          createdAt = clock.now,
          senderUserId = None,
          recipientSocialUserId = fortyTwoSocialAccount.id,
          state = InvitationStates.ACTIVE
        )))
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

      eliza.sendToUser(userId, Json.arr("new_friends", Set(basicUserRepo.load(senderUserId))))
      eliza.sendToUser(senderUserId, Json.arr("new_friends", Set(basicUserRepo.load(userId))))

      userConnectionRepo.addConnections(userId, Set(senderUserId), requested = true)
    }
  }

  def invite(userId: Id[User], fullSocialId: FullSocialId, subject: Option[String], message: Option[String], source: String): Future[InviteStatus] = {
    getInviteInfo(userId, fullSocialId, subject, message, source).map {
      case inviteInfo if isAllowed(inviteInfo) => processInvite(inviteInfo)
      case _ => InviteStatus.forbidden
    }
  }

  private def processInvite(inviteInfo: InviteInfo): InviteStatus = {
    val inviteStatus = inviteInfo.friend match {
      case Left(socialUserInfo) if socialUserInfo.networkType == SocialNetworks.FACEBOOK => handleFacebookInvite(inviteInfo)
      case Left(socialUserInfo) if socialUserInfo.networkType == SocialNetworks.LINKEDIN => sendInvitationForLinkedIn(inviteInfo)
      case Right(eContact) => sendEmailInvitation(inviteInfo)
      case _ => InviteStatus.unsupportedNetwork
    }
    if (inviteStatus.sent) { reportSentInvitation(inviteStatus.savedInvite.get, inviteInfo) }
    inviteStatus
  }

  private def sendEmailInvitation(inviteInfo:InviteInfo): InviteStatus = {
    val invite = getInvitation(inviteInfo)
    val invitingUser = db.readOnly { implicit session => userRepo.get(inviteInfo.userId) }
    val c = inviteInfo.friend.right.get
    val acceptLink = baseUrl + routes.InviteController.acceptInvite(invite.externalId).url

    val message = inviteInfo.message.getOrElse(s"${invitingUser.firstName} ${invitingUser.lastName} is waiting for you to join Kifi").replace("\n", "<br />")
    val inviterImage = s3ImageStore.avatarUrlByExternalId(Some(200), invitingUser.externalId, invitingUser.pictureName.getOrElse("0"), Some("https"))
    val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(GenericEmailAddress(c.email)))}"

    db.readWrite { implicit session =>
      val electronicMail = ElectronicMail(
        senderUserId = None,
        from = EmailAddresses.INVITATION,
        fromName = Some(s"${invitingUser.firstName} ${invitingUser.lastName} (via Kifi)"),
        to = Seq(GenericEmailAddress(c.email)),
        subject = inviteInfo.subject.getOrElse("Please accept your Kifi Invitation"),
        htmlBody = views.html.email.invitationInlined(invitingUser.firstName, invitingUser.lastName, inviterImage, message, acceptLink, unsubLink).body,
        textBody = Some(views.html.email.invitationText(invitingUser.firstName, invitingUser.lastName, inviterImage, message, acceptLink, unsubLink).body),
        category = NotificationCategory.User.INVITATION,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> emailAddressRepo.getByUser(invitingUser.id.get).address))
      )
      postOffice.sendMail(electronicMail)
      val savedInvite = invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
      log.info(s"[inviteConnection-email] sent invitation to $c")
      InviteStatus.sent(savedInvite)
    }
  }

  private def sendInvitationForLinkedIn(inviteInfo:InviteInfo): InviteStatus = {
    val invite = getInvitation(inviteInfo)
    val userId = inviteInfo.userId
    val savedOpt:Option[Invitation] = db.readWrite(attempts = 2) { implicit s =>
      val socialUserInfo = inviteInfo.friend.left.get
      val me = socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.LINKEDIN).get
      val path = routes.InviteController.acceptInvite(invite.externalId).url
      val subject = inviteInfo.subject.getOrElse(s"Kifi -- ${me.fullName.split(' ')(0)} invites you to Kifi") // todo: same for email
      val message = inviteInfo.message.getOrElse(
        """
          |Please accept my invitation to kifi.
          |
          |What is kifi and why should I join?
          |
          |Kifi (short for keep it find it) allows you to easily keep and tag
          |anything you find online - an article, video, picture, email, anything -
          |then quickly find it on top of your favorite search engine results,
          |together with relevant pages that your friends kept, too.
          |
          |Kifi also lets you message your friends about what you keep and
          |find alongside any web page, to get their opinion or gain from their
          |expertise.
          | """.stripMargin
      )
      val messageWithUrl = s"$message\n$baseUrl$path\n\nKifi is available for desktop only on chrome and firefox.\nSafari, Internet Explorer and mobile are coming soon!"
      log.info(s"[sendInvitationForLinkedIn($userId,${socialUserInfo.id})] subject=$subject message=$messageWithUrl")
      val resp = Await.result(linkedIn.sendMessage(me, socialUserInfo, subject, messageWithUrl), Duration.Inf) // todo(ray): refactor; map future resp
      log.info(s"[sendInvitationForLinkedin($userId,${socialUserInfo.id})] resp=${resp.statusText} resp.body=${resp.body} cookies=${resp.cookies.mkString(",")} headers=${resp.getAHCResponse.getHeaders.toString}")
      if (resp.status != Status.CREATED) { // per LinkedIn doc
        airbrake.notify(s"Failed to send LinkedIn invite for $userId; resp=${resp.statusText} resp.body=${resp.body} invite=$invite; socialUser=$socialUserInfo")
        None
      } else {
        val saved = invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
        log.info(s"[sendInvitationForLinkedin($userId,${socialUserInfo.id})] savedInvite=${saved}")
        Some(saved)
      }
    }
    savedOpt match {
      case Some(saved) => InviteStatus.sent(saved)
      case None =>
        log.error(s"[sendInvitationForLinkedIn($userId)] Cannot send invitation ($invite)")
        InviteStatus.unknownError
    }
  }

  private def handleFacebookInvite(inviteInfo: InviteInfo): InviteStatus = {
    val invitation = getInvitation(inviteInfo)
    val saved = db.readWrite(attempts = 2) { implicit s => invitationRepo.save(invitation) }
    InviteStatus.clientHandle(saved)
  }

  def acceptUrl(invitationId: ExternalId[Invitation]) = URLEncoder.encode(s"$baseUrl${routes.InviteController.acceptInvite(invitationId)}", "UTF-8")
  def fbConfirmUrl(invitationId: ExternalId[Invitation], source: String) = URLEncoder.encode(s"$baseUrl${routes.InviteController.confirmInvite(invitationId, source, None, None)}", "UTF-8")
  def fbInviteUrl(invitationId: ExternalId[Invitation], socialId: SocialId, source: String): String = {
    val link = acceptUrl(invitationId)
    val redirectUri = fbConfirmUrl(invitationId, source)
    s"https://www.facebook.com/dialog/send?app_id=${secureSocialClientIds.facebook}&link=$link&redirect_uri=$redirectUri&to=$socialId"
  }

  def confirmFacebookInvite(id: ExternalId[Invitation], source: String, errorMsg: Option[String], errorCode: Option[Int]): InviteStatus = {
    val (inviteStatus, existingInvitation) = db.readWrite { implicit session =>
      val existingInvitation = invitationRepo.getOpt(id)
      val inviteStatus = existingInvitation match {
        case Some(invite) if invite.state != InvitationStates.INACTIVE => errorCode.map(InviteStatus.facebookError) getOrElse InviteStatus.sent(invite)
        case Some(inactiveInvite) if errorCode.isEmpty => InviteStatus.sent(invitationRepo.save(inactiveInvite.copy(state = InvitationStates.ACTIVE)))
        case _ => errorCode.map(InviteStatus.facebookError) getOrElse InviteStatus.notFound
      }
      (inviteStatus, existingInvitation)
    }

    if (inviteStatus.sent) {
      val activeInvite = inviteStatus.savedInvite.get
      val userId = activeInvite.senderUserId.get
      val friendId = activeInvite.recipientSocialUserId.get
      countInvitationsSent(userId, Left(friendId), existingInvitation).map { invitationsSent =>
        val friendSocialUserInfo = db.readOnly { implicit session => socialUserInfoRepo.get(friendId) }
        val inviteInfo = InviteInfo(userId, Left(friendSocialUserInfo), invitationsSent + 1, None, None, source)
        reportSentInvitation(activeInvite, inviteInfo)
      }
    }

    inviteStatus
  }

  private val resendInvitationLimit = 5
  private def isAllowed(inviteInfo: InviteInfo): Boolean = {
    lazy val (totalAllowedInvites, uniqueInvites) = db.readOnly { implicit session => (
      userValueRepo.getValue(inviteInfo.userId, UserValues.availableInvites), // todo(He-Who-Must-Not-Be-Named): removeme
      invitationRepo.getByUser(inviteInfo.userId).filter(_.state != InvitationStates.INACTIVE).length
    )}
    (1 < inviteInfo.invitationNumber && inviteInfo.invitationNumber <= resendInvitationLimit) || (uniqueInvites < totalAllowedInvites)
  }

  private def getInviteInfo(userId: Id[User], fullSocialId: FullSocialId, subject: Option[String], message: Option[String], source: String): Future[InviteInfo] = {
    val (friendFuture, invitationsSentFuture) = fullSocialId.identifier match {
      case Left(socialId) =>
        val friendSocialUserInfo =  db.readOnly() { implicit session =>socialUserInfoRepo.get(socialId, fullSocialId.network) }
        val invitationsSentFuture = countInvitationsSent(userId, Left(friendSocialUserInfo.id.get))
        (Future.successful(Left(friendSocialUserInfo)), invitationsSentFuture)
      case Right(emailAddress) => {
        val friendEContactFuture = abook.getOrCreateEContact(userId, emailAddress).map(eContactTry => Right(eContactTry.get))
        val invitationsSentFuture = countInvitationsSent(userId, Right(emailAddress))
        (friendEContactFuture, invitationsSentFuture)
      }
    }

    for {
      friend <- friendFuture
      invitationsSent <- invitationsSentFuture
    } yield InviteInfo(userId, friend, invitationsSent + 1, subject, message, source)
  }

  private def countInvitationsSent(userId: Id[User], friendId: Either[Id[SocialUserInfo], String], knownInvitation: Option[Invitation] = None): Future[Int] = {
    // Optimization to avoid calling ABook in the most common cases (ie first time social invites)
    val mayHaveBeenInvitedAlready = friendId match {
      case Left(socialUserId) => (knownInvitation orElse db.readOnly { implicit session =>
        invitationRepo.getBySenderIdAndRecipientSocialUserId(userId, socialUserId)
      }).exists(_.state != InvitationStates.INACTIVE)
      case Right(emailAddress) => knownInvitation.isEmpty || knownInvitation.get.state != InvitationStates.INACTIVE
    }
    if (mayHaveBeenInvitedAlready) abook.countInvitationsSent(userId, friendId) else Future.successful(0)
  }

  private def getInvitation(inviteInfo: InviteInfo): Invitation = {
    val existingInvitation = db.readOnly() { implicit session =>
      inviteInfo.friend match {
        case Left(friendSocialUserInfo) => invitationRepo.getBySenderIdAndRecipientSocialUserId(inviteInfo.userId, friendSocialUserInfo.id.get)
        case Right(friendEContact) => invitationRepo.getBySenderIdAndRecipientEContactId(inviteInfo.userId, friendEContact.id.get)
      }
    }

    existingInvitation getOrElse Invitation(
      senderUserId = Some(inviteInfo.userId),
      recipientSocialUserId = inviteInfo.friend.left.toOption.map(_.id.get),
      recipientEContactId = inviteInfo.friend.right.toOption.map(_.id.get),
      state = InvitationStates.INACTIVE
    )
  }

  private def reportSentInvitation(invite: Invitation, inviteInfo: InviteInfo): Unit = SafeFuture {
    // Report immediately to ABook
    val senderId = inviteInfo.userId
    val socialNetwork = inviteInfo.friend.left.map(_.networkType).left getOrElse SocialNetworks.EMAIL
    val friendSocialId = invite.recipientSocialUserId
    val friendEContactId = if (socialNetwork != SocialNetworks.EMAIL) None else invite.recipientEContactId
    shoeboxRichConnectionCommander.processUpdateImmediate(RecordInvitation(senderId, friendSocialId, friendEContactId, inviteInfo.invitationNumber))

    // Report to Mixpanel
    val contextBuilder = eventContextBuilder()
    contextBuilder += ("action", "sent")
    contextBuilder += ("socialNetwork", socialNetwork.toString)
    contextBuilder += ("inviteId", invite.externalId.id)
    contextBuilder += ("invitationNumber", inviteInfo.invitationNumber)
    inviteInfo.source.foreach { source => contextBuilder += ("source", source) }
    invite.recipientEContactId.foreach { eContactId => contextBuilder += ("recipientEContactId", eContactId.toString) }
    invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }
    heimdal.trackEvent(UserEvent(senderId, contextBuilder.build, UserEventTypes.INVITED))
  }

  private def reportReceivedInvitation(receiverId: Id[User], socialUserNetwork: SocialNetworkType, invite: Invitation, actuallyAccepted: Boolean): Unit =
    invite.senderUserId.foreach { senderId =>
      SafeFuture {
        val invitedVia = socialUserNetwork match {
          case SocialNetworks.FORTYTWO => SocialNetworks.EMAIL
          case other => other
        }
        val contextBuilder = new HeimdalContextBuilder
        contextBuilder += ("socialNetwork", invitedVia.toString)
        contextBuilder += ("inviteId", invite.externalId.id)
        invite.recipientEContactId.foreach { eContactId => contextBuilder += ("recipientEContactId", eContactId.toString) }
        invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }

        if (actuallyAccepted) {
          val acceptedAt = invite.updatedAt
          // Credit the sender of the accepted invite
          contextBuilder += ("action", "accepted")
          contextBuilder += ("recipientId", receiverId.toString)
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
        heimdal.trackEvent(UserEvent(receiverId, contextBuilder.build, UserEventTypes.JOINED, invite.createdAt))
      }
    }
}
