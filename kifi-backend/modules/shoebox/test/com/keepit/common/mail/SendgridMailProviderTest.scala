package com.keepit.common.mail

import com.keepit.test._
import play.api.templates.Html
import org.specs2.mutable.Specification
import com.keepit.common.db.slick._
import com.keepit.model.NotificationCategory

class SendgridMailProviderTest extends Specification with ShoeboxTestInjector {

  "SendgridMailProvider" should {
    "send email" in {
      withDb() { implicit injector =>
        val mail = inject[Database].readWrite { implicit s =>
          inject[ElectronicMailRepo].save(ElectronicMail(
            from = SystemEmailAddress.ENG,
            fromName = Some("Marvin"),
            to = List(SystemEmailAddress.ENG),
            subject = "Email from test case",
            htmlBody = views.html.main("KiFi")(Html("<b>thanks</b>")).body,
            category = NotificationCategory.System.HEALTHCHECK))
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

        //         usually using instance[PostOffice].sendMail(mail
        //        instance[SendgridMailProvider].sendMailToSendgrid(mail)
        inject[Database].readOnlyMaster { implicit s =>
          val loaded = inject[ElectronicMailRepo].get(mail.id.get)
          loaded.from === mail.from
          loaded.fromName === mail.fromName
          loaded.state === ElectronicMailStates.PREPARING
        }
      }
    }
  }
}
