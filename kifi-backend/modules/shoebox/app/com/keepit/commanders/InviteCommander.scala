package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.slick._
import com.keepit.common.mail._
import com.keepit.model._
import com.keepit.social.{SocialId, SocialNetworkType, SocialNetworks}

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.time._
import com.keepit.common.db.slick.DBSession.RWSession
import scala.util.Try
import com.keepit.eliza.ElizaServiceClient
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.social.{LinkedInSocialGraph, BasicUserRepo}
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.controllers.website.routes
import com.keepit.common.logging.Logging
import com.keepit.model.SocialConnection
import scala.Some
import com.keepit.model.Invitation
import com.keepit.common.store.S3ImageStore
import play.api.http.Status
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Await
import scala.concurrent.duration.Duration

case class FullSocialId(network:String, id:String)
object FullSocialId {
  def apply(s:String):FullSocialId = {
    val splitted = s.split("/")
    require(splitted.length == 2, s"invalid fullSocialId($s)")
    new FullSocialId(splitted(0), splitted(1))
  }
}
case class InviteInfo(fullSocialId:FullSocialId, subject:Option[String], message:Option[String])
object InviteInfo {
  implicit val format = (
    (__ \ 'fullSocialId).format[String].inmap((s:String) => FullSocialId(s), unlift((fid:FullSocialId) => Some(s"${fid.network}/${fid.id}"))) and
    (__ \ 'subject).formatNullable[String] and
    (__ \ 'message).formatNullable[String]
  )(InviteInfo.apply, unlift(InviteInfo.unapply))
}

class InviteCommander @Inject() (
  db: Database,
  airbrake: AirbrakeNotifier,
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
  clock: Clock,
  s3ImageStore: S3ImageStore,
  emailOptOutCommander: EmailOptOutCommander) extends Logging {

  def getOrCreateInvitesForUser(userId: Id[User], invId: Option[ExternalId[Invitation]]) = {
    db.readOnly { implicit s =>
      val userSocialAccounts = socialUserInfoRepo.getByUser(userId)
      val cookieInvite = invId.flatMap { inviteExtId =>
        Try(invitationRepo.get(inviteExtId)).map { invite =>
          val invitedSocial = userSocialAccounts.find(_.id == invite.recipientSocialUserId)
          val detectedInvite = if (invitedSocial.isEmpty && userSocialAccounts.nonEmpty) {
            // User signed up using a different social account than what we know about.
            invite.copy(recipientSocialUserId = userSocialAccounts.head.id)
          } else {
            invite
          }
          invite.recipientSocialUserId match {
            case Some(rsui) => socialUserInfoRepo.get(rsui) -> detectedInvite
            case None => socialUserInfoRepo.get(userSocialAccounts.head.id.get) -> detectedInvite
          }
        }.toOption
      }

      val otherInvites = for {
        su <- userSocialAccounts
        invite <- invitationRepo.getByRecipientSocialUserId(su.id.get)
      } yield (su, invite)

      val existingInvites = otherInvites.toSet ++ cookieInvite.map(Set(_)).getOrElse(Set.empty)

      if (existingInvites.isEmpty) {
        userSocialAccounts.find(_.networkType == SocialNetworks.FORTYTWO).map { su =>
          Set(su -> Invitation(
            createdAt = clock.now,
            senderUserId = None,
            recipientSocialUserId = su.id,
            state = InvitationStates.ACTIVE
          ))
        }.getOrElse(Set())
      } else {
        existingInvites
      }
    }
  }

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
        // Swallow exceptions currently because we have a constraint that we user can only be invited once
        // However, this can get violated if the user signs up with a different social network than we were expecting
        // and we change the recipientSocialUserId. When the constraint is removed, this Try{} can be too.
        Try {
          connectInvitedUsers(userId, invite)
          if (Set(InvitationStates.INACTIVE, InvitationStates.ACTIVE).contains(invite.state)) {
            val acceptedInvite = invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
            reportReceivedInvitation(userId, su.networkType, acceptedInvite, invId == Some(invite.externalId))
          }
        }
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

  def sendEmailInvitation(c:EContact, invite:Invitation, invitingUser:User, url:String, inviteInfo:InviteInfo)(implicit rw:RWSession) {
    val acceptLink = url + routes.InviteController.acceptInvite(invite.externalId).url

    val message = inviteInfo.message.getOrElse(s"${invitingUser.firstName} ${invitingUser.lastName} is waiting for you to join Kifi").replace("\n", "<br />")
    val inviterImage = s3ImageStore.avatarUrlByExternalId(Some(200), invitingUser.externalId, invitingUser.pictureName.getOrElse("0"), Some("https"))
    val unsubLink = s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(emailOptOutCommander.generateOptOutToken(GenericEmailAddress(c.email)))}"

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
    log.info(s"[inviteConnection-email] sent invitation to $c")
  }

  def sendInvitationForContact(userId: Id[User], c: EContact, user: User, url:String, inviteInfo:InviteInfo):Unit = {
    db.readWrite { implicit s =>
      val inviteOpt = invitationRepo.getBySenderIdAndRecipientEContactId(userId, c.id.get)
      log.info(s"[inviteConnection-email] inviteOpt=$inviteOpt")
      inviteOpt match {
        case Some(alreadyInvited) if alreadyInvited.state != InvitationStates.INACTIVE => {
          sendEmailInvitation(c, alreadyInvited, user, url, inviteInfo)
        }
        case inactiveOpt => {
          val totalAllowedInvites = userValueRepo.getValue(userId, UserValues.availableInvites)
          val currentInvitations = invitationRepo.getByUser(userId).filter(_.state != InvitationStates.INACTIVE)
          if (currentInvitations.length < totalAllowedInvites) {
            val invite = inactiveOpt map {
              _.copy(senderUserId = Some(userId))
            } getOrElse {
              Invitation(
                senderUserId = Some(userId),
                recipientSocialUserId = None,
                recipientEContactId = c.id,
                state = InvitationStates.INACTIVE
              )
            }
            sendEmailInvitation(c, invite, user, url, inviteInfo)
            invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
            reportSentInvitation(invite, SocialNetworks.EMAIL)
          }
        }
      }
    }
  }

  case class InviteStatus(sent:Boolean, savedInvite:Option[Invitation], code:String)

  def sendInvitationForLinkedIn(userId:Id[User], invite:Invitation, socialUserInfo:SocialUserInfo, url:String, inviteInfo:InviteInfo):InviteStatus = {
    val savedOpt:Option[Invitation] = db.readWrite(attempts = 2) { implicit s =>
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
      val messageWithUrl = s"$message\n$url$path\n\nKifi is available for desktop only on chrome and firefox.\nSafari, Internet Explorer and mobile are coming soon!"
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
      case Some(saved) =>
        reportSentInvitation(invite, SocialNetworks.LINKEDIN)
        InviteStatus(true, savedOpt, "invite_sent")
      case None =>
        log.error(s"[sendInvitationForLinkedIn($userId)] Cannot send invitation ($invite)")
        InviteStatus(false, None, "unknown error")
    }
  }

  def processSocialInvite(userId: Id[User], inviteInfo:InviteInfo, url:String):InviteStatus = {
    def sendInvitationCB(socialUserInfo:SocialUserInfo, invite:Invitation):InviteStatus = {
      socialUserInfo.networkType match {
        case SocialNetworks.FACEBOOK =>
          val saved = db.readWrite(attempts = 2) { implicit s => invitationRepo.save(invite) }
          InviteStatus(false, Some(saved), "client_handle")
        case SocialNetworks.LINKEDIN =>
          sendInvitationForLinkedIn(userId, invite, socialUserInfo, url, inviteInfo)
        case _ =>
          InviteStatus(false, None, "unsupported_social_network")
      }
    }
    filterSocialInvite(inviteInfo, userId, sendInvitationCB)
  }

  def filterSocialInvite(inviteInfo:InviteInfo, userId:Id[User], cb:(SocialUserInfo, Invitation) => InviteStatus):InviteStatus = {
    db.readWrite(attempts = 2) {
      implicit rw =>
        val socialUserInfo = socialUserInfoRepo.get(SocialId(inviteInfo.fullSocialId.id), SocialNetworkType(inviteInfo.fullSocialId.network))
        invitationRepo.getBySenderIdAndRecipientSocialUserId(userId, socialUserInfo.id.get) match {
          case Some(currInvite) if currInvite.state != InvitationStates.INACTIVE => cb(socialUserInfo, currInvite)
          case inactiveOpt =>
            val totalAllowedInvites = userValueRepo.getValue(userId, UserValues.availableInvites) // todo(He-Who-Must-Not-Be-Named): removeme
            val currentInvitations = invitationRepo.getByUser(userId).filter(_.state != InvitationStates.INACTIVE)
            if (currentInvitations.length < totalAllowedInvites) {
              val invite = inactiveOpt map {
                _.copy(senderUserId = Some(userId))
              } getOrElse Invitation(
                senderUserId = Some(userId),
                recipientSocialUserId = socialUserInfo.id,
                state = InvitationStates.INACTIVE
              )
              cb(socialUserInfo, invite)
            } else {
              InviteStatus(false, None, "over_invite_limit")
            }
        }
    }
  }

  def reportSentInvitation(invite: Invitation, socialNetwork: SocialNetworkType): Unit = invite.senderUserId.foreach { senderId =>
    SafeFuture {
      val contextBuilder = eventContextBuilder()
      contextBuilder += ("action", "sent")
      contextBuilder += ("socialNetwork", socialNetwork.toString)
      contextBuilder += ("inviteId", invite.externalId.id)
      invite.recipientEContactId.foreach { eContactId => contextBuilder += ("recipientEContactId", eContactId.toString) }
      invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }
      heimdal.trackEvent(UserEvent(senderId, contextBuilder.build, UserEventTypes.INVITED))
    }
  }

  def reportReceivedInvitation(receiverId: Id[User], socialUserNetwork: SocialNetworkType, invite: Invitation, actuallyAccepted: Boolean): Unit =
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
