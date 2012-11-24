package com.keepit.common.mail

import com.keepit.test._
import com.keepit.TestAkkaSystem
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.templates.Html
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import com.keepit.common.db.CX


@RunWith(classOf[JUnitRunner])
class SendgridMailProviderTest extends Specification with TestAkkaSystem {

  "SendgridMailProvider" should {
    "send email" in {
      running(new ShoeboxApplication().withFakeHealthcheck()) {
        val mail = CX.withConnection{ implicit conn =>
          ElectronicMail(
              from = EmailAddresses.ENG,
              to = EmailAddresses.ENG,
              subject = "Email from test case",
              htmlBody = views.html.main("KiFi")(Html("<b>thanks</b>")).body,
              category = PostOffice.Categories.HEALTHCHECK).save
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
        CX.withConnection{ implicit conn =>
          val loaded = ElectronicMail.get(mail.id.get)
          loaded.state === ElectronicMail.States.PREPARING
        }
      }
    }
  }
}
