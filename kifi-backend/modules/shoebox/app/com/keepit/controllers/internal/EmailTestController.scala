package com.keepit.controllers.internal

import com.google.inject.Inject

import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.mail.{PostOffice, LocalPostOffice, ElectronicMail, EmailAddresses, GenericEmailAddress}
import com.keepit.common.db.slick.Database

import play.api.mvc.Action

import play.api.templates.Html

class EmailTestController @Inject() (postOffice: LocalPostOffice, db: Database) extends ShoeboxServiceController {

  def sendableAction(name: String)(body: => Html) = Action { request =>
    val result = body
    request.queryString.get("sendTo").flatMap(_.headOption).foreach{ email =>
      db.readWrite{ implicit session =>
        postOffice.sendMail(ElectronicMail(
          senderUserId = None,
          from = EmailAddresses.ENG,
          fromName = Some("Email Test"),
          to = Seq(GenericEmailAddress(email)),
          subject = "Email Template Test: " + name,
          htmlBody = result.body,
          category = PostOffice.Categories.ALL)
        )
      }
    }

    Ok(result)
  }

  val templates = Map(
    "friendJoined" -> views.html.email.friendJoined("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200"),
    "friendJoinedInlined" -> views.html.email.friendJoinedInlined("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200"),
    "invitation" -> views.html.email.invitation("Tester", "MacTest", "http://lorempixel.com/200/200/"),
    "invitationInlined" -> views.html.email.invitationInlined("Tester", "MacTest", "http://lorempixel.com/200/200/"),
    "friendRequestAccepted" -> views.html.email.friendRequestAccepted("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200/cats", "http://lorempixel.com/200/200/people"),
    "friendRequestAcceptedInlined" -> views.html.email.friendRequestAcceptedInlined("Stephen", "Tester", "MacTest", "http://lorempixel.com/200/200/cats", "http://lorempixel.com/200/200/people"),
    "friendRequest" -> views.html.email.friendRequest("Stephen", "Tester MacTest", "http://lorempixel.com/200/200/cats"),
    "friendRequestInlined" -> views.html.email.friendRequestInlined("Stephen", "Tester MacTest", "http://lorempixel.com/200/200/cats"),
    "welcome" -> views.html.email.welcome("Stephen", "https://www.kifi.com"),
    "welcomeInlined" -> views.html.email.welcomeInlined("Stephen", "https://www.kifi.com")
  )

  def testEmail(name: String) = sendableAction(name) {
    templates(name)
  }


}
