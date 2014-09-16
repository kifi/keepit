package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.commanders.emails.{ ContactJoinedEmailSender, FriendRequestEmailSender, WelcomeEmailSender, FriendConnectionMadeEmailSender, FeatureWaitlistEmailSender, ResetPasswordEmailSender }
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.{ ElectronicMail, EmailAddress, LocalPostOffice, SystemEmailAddress }
import com.keepit.model.{ NotificationCategory, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.Action
import play.twirl.api.Html

class EmailTestController @Inject() (
    postOffice: LocalPostOffice,
    db: Database,
    welcomeEmailSender: WelcomeEmailSender,
    resetPasswordSender: ResetPasswordEmailSender,
    waitListSender: FeatureWaitlistEmailSender,
    friendRequestEmailSender: FriendRequestEmailSender,
    contactJoinedEmailSender: ContactJoinedEmailSender,
    friendRequestAcceptedSender: FriendConnectionMadeEmailSender) extends ShoeboxServiceController {

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

    val emailF = name match {
      case "welcomeEmail" => welcomeEmailSender.sendToUser(userId)
      case "resetPassword" => resetPasswordSender.sendToUser(userId, sendTo)
      case "mobileWaitlist" =>
        val feature = request.getQueryString("feature").getOrElse(waitListSender.emailTriggers.keys.head)
        waitListSender.sendToUser(sendTo, feature)
      case "friendRequest" => friendRequestEmailSender.sendToUser(userId, friendId)
      case "friendRequestAccepted" => friendRequestAcceptedSender.sendToUser(userId, friendId, NotificationCategory.User.FRIEND_ACCEPTED)
      case "connectionMade" => friendRequestAcceptedSender.sendToUser(userId, friendId, NotificationCategory.User.CONNECTION_MADE)
      case "contactJoined" => contactJoinedEmailSender.sendToUser(userId, friendId)
    }

    emailF.map(email => Ok(email.htmlBody.value))
  }
}

