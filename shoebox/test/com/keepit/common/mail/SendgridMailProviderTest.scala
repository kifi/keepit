package com.keepit.common.mail

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.common.db.slick._

class SendgridMailProviderTest extends Specification {

  "SendgridMailProvider" should {
    "send email" in {
      running(new ShoeboxApplication()) {
        val mail = inject[Database].readWrite { implicit s =>
          inject[ElectronicMailRepo].save(ElectronicMail(
              from = EmailAddresses.ENG,
              fromName = Some("Marvin"),
              to = List(EmailAddresses.ENG),
              subject = "Email from test case",
              htmlBody = views.html.main("KiFi")(Html("<b>thanks</b>")).body,
              category = PostOffice.Categories.HEALTHCHECK))
        }
        mail.htmlBody.trim === """<!DOCTYPE html>

<html>
    <head>
        <title>KiFi</title>
        <link rel="stylesheet" media="screen" href="/assets/stylesheets/main.css">
        <link rel="shortcut icon" type="image/png" href="/assets/images/favicon.png">
        <script src="/assets/javascripts/jquery-1.7.1.min.js" type="text/javascript"></script>
    </head>
    <body>
        <b>thanks</b>
    </body>
</html>""".trim

//         usually using inject[PostOffice].sendMail(mail
//        inject[SendgridMailProvider].sendMailToSendgrid(mail)
        inject[Database].readOnly { implicit s =>
          val loaded = inject[ElectronicMailRepo].get(mail.id.get)
          loaded.from === mail.from
          loaded.fromName === mail.fromName
          loaded.state === ElectronicMailStates.PREPARING
        }
      }
    }
  }
}
