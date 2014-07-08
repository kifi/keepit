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
import com.keepit.model._
import com.keepit.social.{SocialNetworkType, SocialNetworks}
import com.keepit.common.ImmediateMap

import java.net.URLEncoder

import play.api.http.Status
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.Future
import scala.util.Try
import com.keepit.common.queue.{RecordInvitation, CancelInvitation}
import com.keepit.abook.{RichContact, ABookServiceClient}

import akka.actor.Scheduler
import org.joda.time.DateTime
import com.keepit.model.SocialConnection
import play.api.libs.json.JsString
import scala.Some
import com.keepit.inject.FortyTwoConfig
import com.keepit.social.SecureSocialClientIds
import com.keepit.common.mail.EmailAddress
import com.keepit.social.SocialId
import com.keepit.commanders.emails.EmailOptOutCommander

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

case class Invitee(name: String, fullSocialId: FullSocialId, pictureUrl: Option[String], canBeInvited: Boolean, lastInvitedAt: Option[DateTime])

object Invitee {
  implicit val format = (
      (__ \ 'name).format[String] and
      (__ \ 'fullSocialId).format[FullSocialId] and
      (__ \ 'pictureUrl).formatNullable[String] and
      (__ \ 'canBeInvited).format[Boolean] and
      (__ \ 'lastInvitedAt).formatNullable(DateTimeJsonFormat)
    )(Invitee.apply, unlift(Invitee.unapply))
}

case class InviteInfo(userId: Id[User], friend: Either[SocialUserInfo, RichContact], invitationNumber: Int, subject: Option[String], message: Option[String], source: String)

case class InviteStatus(sent: Boolean, savedInvite: Option[Invitation], code: String)

object InviteStatus {
  def sent(savedInvite: Invitation) = {
    require(savedInvite.state == InvitationStates.ACTIVE, "Sent invite was not saved with an active state")
    InviteStatus(true, Some(savedInvite), "invite_sent")
  }
  def clientHandle(savedInvite: Invitation) = InviteStatus(false, Some(savedInvite), "client_handle")
  def facebookError(code: Int, savedInvite: Option[Invitation]) = InviteStatus(false, savedInvite, s"facebook_error_$code")
  def linkedInError(code: Int) = InviteStatus(false, None, s"linkedin_error_{$code}")
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
  actionAuthenticator: ActionAuthenticator,
  postOffice: LocalPostOffice,
  emailAddressRepo: UserEmailAddressRepo,
  socialConnectionRepo: SocialConnectionRepo,
  eliza: ElizaServiceClient,
  basicUserRepo: BasicUserRepo,
  linkedIn: LinkedInSocialGraph,
  eventContextBuilder: HeimdalContextBuilderFactory,
  heimdal: HeimdalServiceClient,
  abook: ABookServiceClient,
  clock: Clock,
  s3ImageStore: S3ImageStore,
  emailOptOutCommander: EmailOptOutCommander,
  shoeboxRichConnectionCommander: ShoeboxRichConnectionCommander,
  scheduler: Scheduler) extends Logging {

  private lazy val baseUrl = fortytwoConfig.applicationBaseUrl

  def markPendingInvitesAsAccepted(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    val anyPendingInvites = getOrCreateInvitesForUser(userId, invId).filter { case (invite, _) =>
      Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(invite.state)
    }
    db.readWrite { implicit s =>
      val actuallyInvitedUser = anyPendingInvites.collectFirst { case (invite, _) if invite.senderUserId.isDefined =>
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

      val otherSocialInvites = for {
        su <- userSocialAccounts
        invite <- invitationRepo.getByRecipientSocialUserId(su.id.get)
      } yield (invite, su.networkType)

      val otherEmailInvites = for {
        emailAccount <- userEmailAccounts if emailAccount.verified
        invite <- invitationRepo.getByRecipientEmailAddress(emailAccount.address)
      } yield (invite, SocialNetworks.EMAIL)

      val existingInvites = otherSocialInvites.toSet ++ otherEmailInvites.toSet ++ cookieInvite.toSet

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
      notifyClientsOfConnection(userId, senderUserId);
      userConnectionRepo.addConnections(userId, Set(senderUserId), requested = true)
    }
  }

  private def notifyClientsOfConnection(user1Id: Id[User], user2Id: Id[User]) = {
    delay {
      val (user1, user2) = db.readOnlyMaster { implicit session => basicUserRepo.load(user1Id) -> basicUserRepo.load(user2Id) }
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

  def invite(userId: Id[User], fullSocialId: FullSocialId, subject: Option[String], message: Option[String], source: String): Future[InviteStatus] = {
    getInviteInfo(userId, fullSocialId, subject, message, source).flatMap {
      case inviteInfo if isAllowed(inviteInfo) => processInvite(inviteInfo)
      case _ => Future.successful(InviteStatus.forbidden)
    }
  }

  private def processInvite(inviteInfo: InviteInfo): Future[InviteStatus] = {
    log.info(s"[processInvite] Processing: $inviteInfo")
    val inviteStatusFuture = inviteInfo.friend match {
      case Left(socialUserInfo) if socialUserInfo.networkType == SocialNetworks.FACEBOOK => Future.successful(handleFacebookInvite(inviteInfo))
      case Left(socialUserInfo) if socialUserInfo.networkType == SocialNetworks.LINKEDIN => sendInvitationForLinkedIn(inviteInfo)
      case Right(eContact) => Future.successful(sendEmailInvitation(inviteInfo))
      case _ => Future.successful(InviteStatus.unsupportedNetwork)
    }

    inviteStatusFuture imap { case inviteStatus =>
      log.info(s"[processInvite] Processed: $inviteStatus")
      if (inviteStatus.sent) { reportSentInvitation(inviteStatus.savedInvite.get, inviteInfo) }
      inviteStatus
    }
  }

  private def sendEmailInvitation(inviteInfo:InviteInfo): InviteStatus = {
    val invite = getInvitation(inviteInfo)
    val invitingUser = db.readOnlyMaster { implicit session => userRepo.get(inviteInfo.userId) }
    val c = inviteInfo.friend.right.get
    val acceptLink = baseUrl + routes.InviteController.acceptInvite(invite.externalId).url

    val message = inviteInfo.message.getOrElse(s"${invitingUser.firstName} ${invitingUser.lastName} is waiting for you to join Kifi").replace("\n", "<br />")
    val subject = inviteInfo.subject.getOrElse("Join me on kifi")
    log.info(s"[sendEmailInvitation(${inviteInfo.userId},${c}})] sending with subject=$subject message=$message")
    val inviterImage = s3ImageStore.avatarUrlByExternalId(Some(200), invitingUser.externalId, invitingUser.pictureName.getOrElse("0"), Some("https"))
    val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(c.email))}"


    db.readWrite { implicit session =>
      val electronicMail = ElectronicMail(
        senderUserId = None,
        from = SystemEmailAddress.INVITATION,
        fromName = Some(s"${invitingUser.firstName} ${invitingUser.lastName} (via Kifi)"),
        to = Seq(c.email),
        subject = subject,
        htmlBody = views.html.email.invitationInlined(invitingUser.firstName, invitingUser.lastName, inviterImage, message, acceptLink, unsubLink).body,
        textBody = Some(views.html.email.invitationText(invitingUser.firstName, invitingUser.lastName, inviterImage, message, acceptLink, unsubLink).body),
        category = NotificationCategory.User.INVITATION,
        extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> emailAddressRepo.getByUser(invitingUser.id.get).address))
      )
      postOffice.sendMail(electronicMail)
      val savedInvite = invitationRepo.save(invite.withState(InvitationStates.ACTIVE).withLastSentTime(clock.now()))
      log.info(s"[sendEmailInvitation(${inviteInfo.userId},${c},})] invitation sent")
      InviteStatus.sent(savedInvite)
    }
  }

  private def sendInvitationForLinkedIn(inviteInfo: InviteInfo): Future[InviteStatus] = {
    val invite = getInvitation(inviteInfo)
    val userId = inviteInfo.userId
    val me = db.readOnlyMaster { implicit s => socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.LINKEDIN).get }
    val socialUserInfo = inviteInfo.friend.left.get
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
    linkedIn.sendMessage(me, socialUserInfo, subject, messageWithUrl).map { resp =>
      db.readWrite(attempts = 2) { implicit session =>
        log.info(s"[sendInvitationForLinkedin($userId,${socialUserInfo.id})] resp=${resp.statusText} resp.body=${resp.body} cookies=${resp.cookies.mkString(",")} headers=${resp.getAHCResponse.getHeaders.toString}")
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
    val invitation = getInvitation(inviteInfo)
    val saved = db.readWrite(attempts = 2) { implicit s => invitationRepo.save(invitation) }
    log.info(s"[handleFacebookInvite(${inviteInfo.userId}, ${invitation.recipientSocialUserId.get}})] Persisted ${invitation}")
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
        case Some(invite) if errorCode.isEmpty => InviteStatus.sent(invitationRepo.save(invite.copy(state = InvitationStates.ACTIVE).withLastSentTime(clock.now())))
        case _ => errorCode.map(InviteStatus.facebookError(_, existingInvitation)) getOrElse InviteStatus.notFound
      }
      (inviteStatus, existingInvitation)
    }

    if (inviteStatus.sent) {
      val activeInvite = inviteStatus.savedInvite.get
      val userId = activeInvite.senderUserId.get
      val friendId = activeInvite.recipientSocialUserId.get
      log.info(s"[confirmFacebookInvite(${id})] Confirmed ${inviteStatus}")
      countInvitationsSent(userId, Left(friendId), existingInvitation).map { invitationsSent =>
        val friendSocialUserInfo = db.readOnlyMaster { implicit session => socialUserInfoRepo.get(friendId) }
        val inviteInfo = InviteInfo(userId, Left(friendSocialUserInfo), invitationsSent + 1, None, None, source)
        reportSentInvitation(activeInvite, inviteInfo)
      }
    } else { log.error(s"[confirmFacebookInvite(${id}})] Failed to confirmed ${inviteStatus}") }
    inviteStatus
  }

  private class IllegalInvitationException(message: String) extends Exception(message)
  private def isInvitationAllowed(invitationNumber: Int): Boolean = {
    val resendInvitationLimit = 5
    invitationNumber <= resendInvitationLimit
  }
  private def isAllowed(inviteInfo: InviteInfo): Boolean = {
    val allowed = isInvitationAllowed(inviteInfo.invitationNumber)
    if (!allowed) {
      log.error(s"[InviteCommander.isAllowed] Illegal invitation: $inviteInfo")
      airbrake.notify(new IllegalInvitationException(s"An invitation was rejected: $inviteInfo"))
    }
    allowed
  }

  private def getInviteInfo(userId: Id[User], fullSocialId: FullSocialId, subject: Option[String], message: Option[String], source: String): Future[InviteInfo] = {
    val (friendFuture, invitationsSentFuture) = fullSocialId.identifier match {
      case Left(socialId) =>
        val friendSocialUserInfo =  db.readOnly() { implicit session => socialUserInfoRepo.get(socialId, fullSocialId.network) }
        val invitationsSentFuture = countInvitationsSent(userId, Left(friendSocialUserInfo.id.get))
        (Future.successful(Left(friendSocialUserInfo)), invitationsSentFuture)
      case Right(emailAddress) => {
        val friendRichContactFuture = abook.internKifiContact(userId, BasicContact(emailAddress, fullSocialId.name)).map(Right(_))
        val invitationsSentFuture = countInvitationsSent(userId, Right(emailAddress))
        (friendRichContactFuture, invitationsSentFuture)
      }
    }

    for {
      friend <- friendFuture
      invitationsSent <- invitationsSentFuture
    } yield InviteInfo(userId, friend, invitationsSent + 1, subject, message, source)
  }

  private def countInvitationsSent(userId: Id[User], friendId: Either[Id[SocialUserInfo], EmailAddress], knownInvitation: Option[Invitation] = None): Future[Int] = {
    // Optimization to avoid calling ABook in the most common cases (ie first time social invites)
    val mayHaveBeenInvitedAlready = friendId match {
      case Left(socialUserId) => (knownInvitation orElse db.readOnlyMaster { implicit session =>
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
        case Right(friendEContact) => invitationRepo.getBySenderIdAndRecipientEmailAddress(inviteInfo.userId, friendEContact.email)
      }
    }

    existingInvitation getOrElse Invitation(
      senderUserId = Some(inviteInfo.userId),
      recipientSocialUserId = inviteInfo.friend.left.toOption.map(_.id.get),
      recipientEmailAddress = inviteInfo.friend.right.toOption.map(_.email),
      state = InvitationStates.INACTIVE
    )
  }

  private def reportSentInvitation(invite: Invitation, inviteInfo: InviteInfo): Unit = SafeFuture {
    // Report immediately to ABook
    val senderId = inviteInfo.userId
    val socialNetwork = inviteInfo.friend.left.map(_.networkType).left getOrElse SocialNetworks.EMAIL
    val friendSocialId = invite.recipientSocialUserId
    val friendEmailAddress = if (socialNetwork != SocialNetworks.EMAIL) None else invite.recipientEmailAddress
    shoeboxRichConnectionCommander.processUpdateImmediate(RecordInvitation(senderId, friendSocialId, friendEmailAddress, inviteInfo.invitationNumber))

    // Report to Mixpanel
    val contextBuilder = eventContextBuilder()
    contextBuilder += ("action", "sent")
    contextBuilder += ("socialNetwork", socialNetwork.toString)
    contextBuilder += ("inviteId", invite.externalId.id)
    contextBuilder += ("invitationNumber", inviteInfo.invitationNumber)
    contextBuilder += ("source", inviteInfo.source)
    invite.recipientEmailAddress.foreach { emailAddress => contextBuilder += ("recipientEmailAddress", emailAddress.toString) }
    invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }
    heimdal.trackEvent(UserEvent(senderId, contextBuilder.build, UserEventTypes.INVITED, invite.lastSentAt getOrElse invite.createdAt))
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

  def getRipestInvitees(userId: Id[User], page: Int, pageSize: Int): Future[Seq[Invitee]] = {
    abook.getRipestFruits(userId, page, pageSize).map { ripestFruits =>
      val (emailConnections, socialConnections) = (ripestFruits.partition(_.connectionType == SocialNetworks.EMAIL))
      db.readOnlyMaster { implicit session =>
        val lastInvitedAtByEmailAddress = invitationRepo.getLastInvitedAtBySenderIdAndRecipientEmailAddresses(userId, emailConnections.flatMap(_.friendEmailAddress))
        val lastInvitedAtBySocialUserId = invitationRepo.getLastInvitedAtBySenderIdAndRecipientSocialUserIds(userId, socialConnections.flatMap(_.friendSocialId))
        ripestFruits.map { richConnection => richConnection.connectionType match {
          case SocialNetworks.EMAIL =>
            val emailAddress = richConnection.friendEmailAddress.get
            val name = richConnection.friendName getOrElse emailAddress.address
            val fullSocialId = FullSocialId(richConnection.connectionType, Right(emailAddress))
            val canBeInvited = isInvitationAllowed(richConnection.invitationsSent + 1)
            Invitee(name, fullSocialId, None, canBeInvited, lastInvitedAtByEmailAddress.get(emailAddress))
          case _ =>
            val socialUserId = richConnection.friendSocialId.get
            val socialUserInfo = socialUserInfoRepo.get(socialUserId)
            val name = richConnection.friendName getOrElse socialUserInfo.fullName
            val fullSocialId = FullSocialId(richConnection.connectionType, Left(socialUserInfo.socialId))
            val pictureUrl = socialUserInfo.getPictureUrl(80, 80)
            val canBeInvited = isInvitationAllowed(richConnection.invitationsSent + 1)
            Invitee(name, fullSocialId, pictureUrl, canBeInvited, lastInvitedAtBySocialUserId.get(socialUserId))
        }}
      }
    }
  }
}
