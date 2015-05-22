package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.commanders.emails._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.template.{ EmailTip, EmailToSend }
import com.keepit.common.time._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ ElectronicMailRepo, ElectronicMail, EmailAddress, LocalPostOffice, SystemEmailAddress }
import com.keepit.model.{ Invitation, LibraryAccess, LibraryInvite, UserEmailAddress, Library, NotificationCategory, User }
import com.keepit.social.SocialNetworks.FACEBOOK
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Action
import play.twirl.api.Html

import scala.concurrent.Future

class EmailTestController @Inject() (
    postOffice: LocalPostOffice,
    db: Database,
    emailRepo: ElectronicMailRepo,
    emailSenderProvider: EmailSenderProvider,
    emailTemplateSender: EmailTemplateSender) extends ShoeboxServiceController {

  def sendableAction(name: String)(body: => Html) = Action { request =>
    val result = body
    request.queryString.get("sendTo").flatMap(_.headOption).foreach { email =>
      db.readWrite { implicit session =>
        postOffice.sendMail(ElectronicMail(
          senderUserId = None,
          from = SystemEmailAddress.ENG,
          fromName = Some("Email Test"),
          to = Seq(EmailAddress(email)),
          subject = "Email Template Test: " + name,
          htmlBody = result.body,
          category = NotificationCategory.ALL)
        )
      }
    }

    Ok(result)
  }

  def testEmailSender(name: String) = Action.async { request =>
    def userId = Id[User](request.getQueryString("userId").get.toLong)
    def friendId = Id[User](request.getQueryString("friendId").get.toLong)
    def sendTo = EmailAddress(request.getQueryString("sendTo").get)
    def libraryId = Id[Library](request.getQueryString("libraryId").get.toLong)
    def msg = request.getQueryString("msg")
    def tip = request.getQueryString("tip")

    val emailOptF: Option[Future[ElectronicMail]] = Some(name) collect {
      case "gratification" => emailSenderProvider.gratification(userId, Some(sendTo))
      case "kifiInvite" => emailSenderProvider.kifiInvite(sendTo, userId, ExternalId[Invitation]())
      case "welcome" => emailSenderProvider.welcome.sendToUser(userId)
      case "resetPassword" => emailSenderProvider.resetPassword.sendToUser(userId, sendTo)
      case "mobileWaitlist" =>
        val sender = emailSenderProvider.waitList
        val feature = request.getQueryString("feature").getOrElse(sender.emailTriggers.keys.head)
        sender.sendToUser(sendTo, feature)
      case "friendRequest" => emailSenderProvider.friendRequest.sendToUser(userId, friendId)
      case "friendRequestAccepted" => emailSenderProvider.connectionMade.sendToUser(userId, friendId, NotificationCategory.User.FRIEND_ACCEPTED)
      case "connectionMade" => emailSenderProvider.connectionMade.sendToUser(userId, friendId, NotificationCategory.User.CONNECTION_MADE, Some(FACEBOOK))
      case "socialFriendJoined" => emailSenderProvider.connectionMade.sendToUser(userId, friendId, NotificationCategory.User.SOCIAL_FRIEND_JOINED, Some(FACEBOOK))
      case "contactJoined" => emailSenderProvider.contactJoined.sendToUser(userId, friendId)
      case "libraryInviteUser" =>
        implicit val config = PublicIdConfiguration("secret key")
        val invite = LibraryInvite(libraryId = libraryId, inviterId = userId, userId = Some(friendId), access = LibraryAccess.READ_ONLY, message = msg)
        emailSenderProvider.libraryInvite.sendInvite(invite).map(_.get)
      case "libraryInviteNonUser" =>
        implicit val config = PublicIdConfiguration("secret key")
        val invite = LibraryInvite(libraryId = libraryId, inviterId = userId, emailAddress = Some(sendTo), userId = None, access = LibraryAccess.READ_ONLY, message = msg)
        emailSenderProvider.libraryInvite.sendInvite(invite).map(_.get)
      case "confirm" => emailSenderProvider.confirmation.sendToUser(UserEmailAddress(userId = userId, address = sendTo).withVerificationCode(currentDateTime))
      case "tip" if tip.isDefined =>
        val emailTip = EmailTip(tip.get).get
        testEmailTip(Left(userId), emailTip)
      case "activity" =>
        val emailsF = emailSenderProvider.activityFeed(Set(userId))
        val emailIdF = emailsF.map(_.head.get)
        emailIdF map { emailId =>
          db.readOnlyMaster { implicit session =>
            emailRepo.get(emailId)
          }
        }
      case "twitterWaitlist" =>
        emailSenderProvider.twitterWaitlist.sendToUser(sendTo, userId)
    }

    emailOptF.map(_.map(email => Ok(email.htmlBody.value))).
      getOrElse(Future.successful(BadRequest(s"test sender for $name not found")))
  }

  private def testEmailTip(to: Either[Id[User], EmailAddress], tip: EmailTip) = {
    val emailToSend = EmailToSend(
      fromName = None,
      from = SystemEmailAddress.NOTIFICATIONS,
      subject = s"Testing Tip $tip",
      to = to,
      category = NotificationCategory.System.EMAIL_QA,
      htmlTemplate = Html(s"""<br/><br/><div align="center">Email Tip ${tip.name}: Feedback?</div><br/><br/>"""),
      textTemplate = Some(Html(s"[internal] Testing Email Tip: ${tip.name}")),
      tips = Seq(tip)
    )
    emailTemplateSender.send(emailToSend)
  }
}

