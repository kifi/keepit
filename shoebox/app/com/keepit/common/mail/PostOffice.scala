package com.keepit.common.mail

import play.api.Play.current
import play.api.Plugin
import com.keepit.model.EmailAddress
import play.api.templates.Html
import play.api.libs.ws.WS
import play.api.libs.json._
import play.api.libs.ws._
import play.api.http.ContentTypes
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{HealthcheckPlugin, Healthcheck, HealthcheckError}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.LargeString._
import com.keepit.inject._
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import java.util.concurrent.TimeUnit
import akka.actor.Props
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.actor.Cancellable
import com.google.inject.Provider
import com.keepit.common.net.HttpClient
import play.api.libs.concurrent.Promise
import com.keepit.common.net.ClientResponse
import play.api.Mode
import com.google.inject.{ImplementedBy, Inject}

@ImplementedBy(classOf[PostOfficeImpl])
trait PostOffice {
  def sendMail(mail: ElectronicMail): ElectronicMail
}

object PostOffice {
  object Categories {
    val HEALTHCHECK = ElectronicMailCategory("HEALTHCHECK")
    val COMMENT = ElectronicMailCategory("COMMENT")
    val MESSAGE = ElectronicMailCategory("MESSAGE")
    val ADMIN = ElectronicMailCategory("ADMIN")
    val EMAIL_KEEP = ElectronicMailCategory("EMAIL_KEEP")
  }

  val BODY_MAX_SIZE = 524288
}

class PostOfficeImpl @Inject() (
    db: Database,
    mailRepo: ElectronicMailRepo,
    mailer: MailSenderPlugin)
  extends PostOffice with Logging {

  def sendMail(mail: ElectronicMail): ElectronicMail = {
    val prepared = db.readWrite { implicit s =>
      val newMail: ElectronicMail = if(mail.htmlBody.value.size > PostOffice.BODY_MAX_SIZE || (mail.textBody.isDefined && mail.textBody.get.value.size > PostOffice.BODY_MAX_SIZE)) {
        throw new Exception(s"PostOffice attempted to send an email (${mail.externalId}) longer than ${PostOffice.BODY_MAX_SIZE} bytes. Too big!")
      } else {
        mailRepo.save(mail)
      }
      newMail.prepareToSend()
    }
    mailer.processMail(prepared)
    prepared
  }
}

