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

class MailSender(system: ActorSystem, postmarkUrl: String, postmarkToken: String, healthcheck: Healthcheck, httpClient: HttpClient) 
  extends Logging with Plugin {
  
  private def processMail(mail: ElectronicMail) = actor ! ProcessMail(mail, this)
  
  private val actor = system.actorOf(Props { new MailSenderActor(httpClient) })
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(5 seconds, 5 seconds, actor, ProcessOutbox(this))
    )
  }
  
  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }

  private[mail] def processOutbox(): Unit = {
    CX.withConnection { implicit c =>
      ElectronicMail.outbox()
    } foreach { mail =>
      processMail(mail)
    }
  }
  
  /**
   * To test send from curl use the following command:
   * $ curl -iv -H "Accept:application/json" -H "Content-Type:application/json" -H "X-Postmark-Server-Token:61311d22-d1cc-400b-865e-ffb95027251f" -d '{"From":"eng@keepit.com", "To":"eishay@gmail.com", "Subject": "Test from curl", "HtmlBody": "Some body here"}' https://api.postmarkapp.com/email; echo
   */
  private[mail] def sendMailToPostmark(mail: ElectronicMail, httpClient: HttpClient): ElectronicMail = try {
    val json: JsValue = JsObject(Seq(
          "From" -> JsString(mail.from.address),
          "To" -> JsString(mail.to.address),
          "Subject" -> JsString(mail.subject),
          "HtmlBody" -> JsString(mail.htmlBody)
         ))
    val future: Promise[ElectronicMail] = httpClient.longTimeout().withHeaders(
          "Accept" -> ContentTypes.JSON,
          "Content-Type" -> ContentTypes.JSON,
          "X-Postmark-Server-Token" -> postmarkToken
        ).postPromise(postmarkUrl, json).map { response =>
          parsePostmarkResponse(mail, response)
        }
    future.await(1, TimeUnit.MINUTES).get
  } catch {
    case e => 
      val error = healthcheck.addError(HealthcheckError(error = Some(e), callType = Healthcheck.EMAIL, 
        errorMessage = Some("Can't send email from %s to %s: %s".format(mail.from, mail.to, mail.subject))))
      CX.withConnection { implicit c =>
        mail.errorSending("Error: %s".format(error)).save
      }
  }

  /**
   * Good body: {"To":"eishay@gmail.com","SubmittedAt":"2012-07-18T15:05:05.843-04:00","MessageID":"68292d1f-0221-405e-902a-f52dc2b72c0b","ErrorCode":0,"Message":"OK"}
   * Bad body: {"ErrorCode":0,"Message":"Bad or missing server or user API token."}
   *           {"ErrorCode":400,"Message":"Sender signature not defined for From address."}
   *           {"ErrorCode":300,"Message":"Zero recipients specified"}
   */
  private def parsePostmarkResponse(mail: ElectronicMail, response: ClientResponse): ElectronicMail = {
    log.info("postmark response: " + response.status)
    log.info("postmark response body: " + response.body)
    CX.withConnection { implicit c =>
      val body = response.json
      if (response.status != 200 || (body \ "Message").as[String] != "OK") {
        val error = healthcheck.addError(HealthcheckError(callType = Healthcheck.EMAIL, 
            errorMessage = Some("Postmark can't send email from %s to %s: %s. postmark status/message: %s/%s".format(
                mail.from, mail.to, mail.subject, response.status, body))))
        mail.errorSending("Status: %s, Body: %s, Error: %s".format(response.status, body.toString(), error.id)).save
      } else {
        mail.sent(body.toString()).save
      }
    }    
  }
}

private[mail] case class ProcessOutbox(sender: MailSender)
private[mail] case class ProcessMail(mail: ElectronicMail, sender: MailSender)

private[mail] class MailSenderActor(httpClient: HttpClient) extends Actor with Logging {
  
  def receive() = {
    case ProcessOutbox(sender) => sender.processOutbox()
    case ProcessMail(mail, sender) => sender.sendMailToPostmark(mail, httpClient)
    case unknown => throw new Exception("unknown message: %s".format(unknown))
  }
}
