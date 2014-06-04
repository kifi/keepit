package com.keepit.controllers.internal

import com.google.inject.Inject

import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.mail.{PostOffice, LocalPostOffice, ElectronicMail, SystemEmailAddress, EmailAddress}
import com.keepit.common.db.slick.Database


import play.api.mvc.Action

import play.api.templates.Html
import com.keepit.model.NotificationCategory

class EmailTestController @Inject() (postOffice: LocalPostOffice, db: Database) extends ShoeboxServiceController {

  def sendableAction(name: String)(body: => Html) = Action { request =>
    val result = body
    request.queryString.get("sendTo").flatMap(_.headOption).foreach{ email =>
      db.readWrite{ implicit session =>
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


}
