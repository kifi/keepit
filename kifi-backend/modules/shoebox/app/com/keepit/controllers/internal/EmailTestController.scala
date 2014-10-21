package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.commanders.emails._
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.template.{ EmailTip, EmailToSend }
import com.keepit.common.time._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ ElectronicMail, EmailAddress, LocalPostOffice, SystemEmailAddress }
import com.keepit.model.{ Invitation, LibraryAccess, LibraryInvite, UserEmailAddress, Library, NotificationCategory, User }
import com.keepit.social.SocialNetworks.FACEBOOK
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Action
import play.twirl.api.Html

import scala.concurrent.Future

class EmailTestController @Inject() (
    postOffice: LocalPostOffice,
    db: Database,
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

  val templates = Map(
    "friendJoined" -> views.html.email.friendJoined("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200", "https://kifi.com"),
    "friendJoinedInlined" -> views.html.email.friendJoinedInlined("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200", "https://kifi.com"),
    "invitation" -> views.html.email.invitation("Tester", "MacTest", "http://lorempixel.com/200/200/", "Tester MacTest is waiting for you to join Kifi", "https://www.kifi.com", "https://kifi.com"),
    "invitationInlined" -> views.html.email.invitationInlined("Tester", "MacTest", "http://lorempixel.com/200/200/", "Tester MacTest is waiting for you to join Kifi", "https://www.kifi.com", "https://kifi.com"),
    "friendRequestAccepted" -> views.html.email.friendRequestAccepted("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200/cats", "http://lorempixel.com/200/200/people", "https://kifi.com"),
    "friendRequestAcceptedInlined" -> views.html.email.friendRequestAcceptedInlined("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200/cats", "http://lorempixel.com/200/200/people", "https://kifi.com"),
    "friendRequest" -> views.html.email.friendRequest("Stephen", "Tester MacTest", "http://lorempixel.com/200/200/cats", "https://kifi.com"),
    "friendRequestInlined" -> views.html.email.friendRequestInlined("Stephen", "Tester MacTest", "http://lorempixel.com/200/200/cats", "https://kifi.com"),
    "welcome" -> views.html.email.welcome("Stephen", "https://www.kifi.com", "https://kifi.com"),
    "welcomeInlined" -> views.html.email.welcomeInlined("Stephen", "https://www.kifi.com", "https://kifi.com"),
    "welcomeLongInlined" -> views.html.email.welcomeLongInlined("Stephen", "https://www.kifi.com", "https://kifi.com"),
    "mobileWaitlist" -> views.html.email.mobileWaitlist("https://kifi.com"),
    "mobileWaitlistInlined" -> views.html.email.mobileWaitlistInlined("https://kifi.com")
  )

  def testEmail(name: String) = sendableAction(name) {
    templates(name)
  }

  def testEmailSender(name: String) = Action.async { request =>
    def userId = Id[User](request.getQueryString("userId").get.toLong)
    def friendId = Id[User](request.getQueryString("friendId").get.toLong)
    def sendTo = EmailAddress(request.getQueryString("sendTo").get)
    def libraryId = Id[Library](request.getQueryString("libraryId").get.toLong)
    def msg = request.getQueryString("msg")
    def tip = request.getQueryString("tip")

    val emailOptF: Option[Future[ElectronicMail]] = Some(name) collect {
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
        emailSenderProvider.libraryInvite.inviteUserToLibrary(invite).map(_.get)
      case "libraryInviteNonUser" =>
        implicit val config = PublicIdConfiguration("secret key")
        val invite = LibraryInvite(libraryId = libraryId, inviterId = userId, emailAddress = Some(sendTo), userId = None, access = LibraryAccess.READ_ONLY, message = msg)
        emailSenderProvider.libraryInvite.inviteUserToLibrary(invite).map(_.get)
      case "confirm" => emailSenderProvider.confirmation.sendToUser(UserEmailAddress(userId = userId, address = sendTo).withVerificationCode(currentDateTime))
      case "tip" if tip.isDefined =>
        val emailTip = EmailTip(tip.get).get
        testEmailTip(Left(userId), emailTip)
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

