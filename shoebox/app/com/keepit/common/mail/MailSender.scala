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
import akka.actor.Props
import akka.util.duration._
import akka.actor.ActorRef
import akka.actor.Cancellable
import com.google.inject.Provider
import play.api.libs.concurrent.Promise
import com.keepit.common.net.ClientResponse
import java.util.concurrent.TimeUnit
import java.util.Properties
import javax.mail._
import javax.mail.internet._
import javax.mail.event._
import javax.mail.{Authenticator, PasswordAuthentication}

class MailSender(system: ActorSystem, postmarkUrl: String, postmarkToken: String, healthcheck: Healthcheck)
  extends Logging with Plugin {

  private def processMail(mail: ElectronicMail) = actor ! ProcessMail(mail, this)

  private val actor = system.actorOf(Props { new MailSenderActor() })
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

  private class SMTPAuthenticator extends Authenticator {
    override def getPasswordAuthentication(): PasswordAuthentication = {
      val username = "fortytwo"//load from conf
      val password = "keepemailsrunning"
      return new PasswordAuthentication(username, password)
    }
  }

  def sendMailToSendgrid(mail: ElectronicMail): Unit = {
    val props = new Properties()
    props.put("mail.transport.protocol", "smtp")
    props.put("mail.smtp.host", "smtp.sendgrid.net")
    props.put("mail.smtp.port", 587.asInstanceOf[AnyRef])
    props.put("mail.smtp.auth", "true")

    val auth = new SMTPAuthenticator()
    val mailSession = Session.getDefaultInstance(props, auth)
    // uncomment for debugging infos to stdout
    mailSession.setDebug(true)

    val transport = mailSession.getTransport()
    val message = new MimeMessage(mailSession)
    val multipart = new MimeMultipart("alternative")

    val part1 = new MimeBodyPart()
    part1.setText(mail.textBody.getOrElse(""))

    val part2 = new MimeBodyPart()
    part2.setContent(mail.htmlBody, ContentTypes.HTML)

    multipart.addBodyPart(part1)
    multipart.addBodyPart(part2)

    message.setContent(multipart)
    message.setFrom(new InternetAddress(mail.from.address))
    message.setSubject(mail.subject)
    message.addRecipient(Message.RecipientType.TO, new InternetAddress(mail.to.address))

    transport.addTransportListener(new TransportListener() {
      def messageDelivered(e: TransportEvent): Unit = {
        log.info(e)
        CX.withConnection { implicit c =>
          mail.sent(e.toString()).save
        }
      }
      def messageNotDelivered(e: TransportEvent): Unit = {
        log.error(e)
        mailError(mail, e.toString())
      }
      def messagePartiallyDelivered(e: TransportEvent): Unit = {
        log.error(e)
        mailError(mail, e.toString())
      }
    })

    transport.addConnectionListener(new ConnectionListener() {
      def opened(e: ConnectionEvent)  { log.info(e) }
      def closed(e: ConnectionEvent) { log.info(e) }
      def disconnected(e: ConnectionEvent) { log.info(e) }
    })

    transport.connect()
    try {
      transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO))
    } catch {
      case e =>
        log.error(e)
        mailError(mail, e.toString())
    } finally {
      transport.close()
    }

  }

  private def mailError(mail: ElectronicMail, message: String): ElectronicMail = {
    val error = healthcheck.addError(HealthcheckError(callType = Healthcheck.EMAIL,
      errorMessage = Some("Can't send email from %s to %s: %s. Error message: %s".format(mail.from, mail.to, mail.subject, message))))
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

private[mail] class MailSenderActor() extends Actor with Logging {

  def receive() = {
    case ProcessOutbox(sender) => sender.processOutbox()
    case ProcessMail(mail, sender) => sender.sendMailToSendgrid(mail)
    case unknown => throw new Exception("unknown message: %s".format(unknown))
  }
}
