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
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.db.CX
import com.keepit.inject._
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import java.util.concurrent.TimeUnit
import akka.actor.Props
import akka.util.duration._
import akka.actor.ActorRef
import akka.actor.Cancellable
import com.google.inject.Provider
import com.keepit.common.net.HttpClient
import play.api.libs.concurrent.Promise
import com.keepit.common.net.ClientResponse
import play.api.Mode
import com.google.inject.ImplementedBy

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
  }
}

class PostOfficeImpl extends PostOffice with Logging {

  def sendMail(mail: ElectronicMail): ElectronicMail = {
    val prepared = CX.withConnection { implicit c =>
      mail.prepareToSend().save
    }
    inject[MailSenderPlugin].processMail(prepared)
    prepared
  }
}

