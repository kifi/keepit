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
import play.api.mvc.Result

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
  clock: Clock) extends Logging {

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

      val existingInvites = userSocialAccounts.map { su =>
        invitationRepo.getByRecipientSocialUserId(su.id.get).map { inv =>
          su -> inv
        }
      }.flatten.toSet.++(cookieInvite)

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
    val acceptedAt = clock.now
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
            invitationRepo.save(invite.copy(state = InvitationStates.ACCEPTED))
            invite.senderUserId.foreach { senderId =>
              SafeFuture {
                val contextBuilder = new HeimdalContextBuilder
                contextBuilder += ("socialNetwork", su.networkType.toString)
                contextBuilder += ("inviteId", invite.externalId.id)
                invite.recipientEContactId.foreach { eContactId => contextBuilder += ("recipientEContactId", eContactId.toString) }
                invite.recipientSocialUserId.foreach { socialUserId => contextBuilder += ("recipientSocialUserId", socialUserId.toString) }

                if (invId == Some(invite.externalId)) {
                  // Credit the sender of the accepted invite
                  contextBuilder += ("action", "accepted")
                  contextBuilder += ("recipientId", userId.toString)
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
                heimdal.trackEvent(UserEvent(userId, contextBuilder.build, UserEventTypes.JOINED, invite.createdAt))
              }
            }
            if (invite.senderUserId.isEmpty) {
              notifyAdminsAboutNewSignupRequest(userId, su.fullName)
            }
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

  private def notifyAdminsAboutNewSignupRequest(userId: Id[User], name: String)(implicit session: RWSession) = {
    postOffice.sendMail(ElectronicMail(
      senderUserId = None,
      from = EmailAddresses.NOTIFICATIONS,
      fromName = Some("Invitations"),
      to = List(EmailAddresses.INVITATION),
      subject = s"""${name} wants to be let in!""",
      htmlBody = s"""<a href="https://admin.kifi.com/admin/user/${userId}">${name}</a> wants to be let in!\n<br/>
                           Go to the <a href="https://admin.kifi.com/admin/invites?show=accepted">admin invitation page</a> to accept or reject this user.""",
      category = PostOffice.Categories.System.ADMIN))

  }

  def sendEmailInvitation(c:EContact, invite:Invitation, invitingUser:User, url:String, inviteInfo:InviteInfo)(implicit rw:RWSession) {
    val path = routes.InviteController.acceptInvite(invite.externalId).url
    val messageWithUrl = s"${inviteInfo.message getOrElse ""}\n$url$path"
    val electronicMail = ElectronicMail(
      senderUserId = None,
      from = EmailAddresses.INVITATION,
      fromName = Some(s"${invitingUser.firstName} ${invitingUser.lastName} via Kifi"),
      to = List(new EmailAddressHolder {
        override val address = c.email
      }),
      subject = inviteInfo.subject.getOrElse("Join me on the Kifi.com Private Beta"),
      htmlBody = messageWithUrl,
      category = PostOffice.Categories.User.INVITATION)
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
          val totalAllowedInvites = userValueRepo.getValue(userId, "availableInvites").map(_.toInt).getOrElse(20)
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
      val messageWithUrl = s"${inviteInfo.message getOrElse ""}\n$url$path"
      linkedIn.sendMessage(me, socialUserInfo, inviteInfo.subject.getOrElse(""), messageWithUrl)
      val saved = invitationRepo.save(invite.withState(InvitationStates.ACTIVE))
      Some(saved)
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
        invitationRepo.getByRecipientSocialUserId(socialUserInfo.id.get) match {
          case Some(currInvite) if currInvite.state != InvitationStates.INACTIVE =>
            if (currInvite.senderUserId == userId) { // todo: removeme
              cb(socialUserInfo, currInvite)
            } else {
              InviteStatus(false, None, "invite_not_sent")
            }
          case inactiveOpt =>
            val totalAllowedInvites = userValueRepo.getValue(userId, "availableInvites").map(_.toInt).getOrElse(20) // todo: removeme
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


}
