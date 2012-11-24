package com.keepit.common.mail

import com.keepit.common.logging.Logging
import com.keepit.common.db.CX
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.inject._

import java.util.Properties
import javax.mail._
import javax.mail.internet._
import javax.mail.event._
import javax.mail.{Authenticator, PasswordAuthentication}

import com.google.inject.{Inject, Singleton}

import play.api.libs.json._

import play.api.Play.current
import play.api.http.ContentTypes

object SendgridMailProvider {
  val KIFI_MAIL_ID = "kifi-mail-id"
}

@Singleton
class SendgridMailProvider @Inject() () extends Logging {

  private class SMTPAuthenticator extends Authenticator {
    override def getPasswordAuthentication(): PasswordAuthentication = {
      val username = "fortytwo"//load from conf
      val password = "keepemailsrunning"
      return new PasswordAuthentication(username, password)
    }
  }

  lazy val mailSession: Session = {
    val props = new Properties()
    props.put("mail.transport.protocol", "smtp")
    props.put("mail.smtp.host", "smtp.sendgrid.net")
    props.put("mail.smtp.port", 587.asInstanceOf[AnyRef])
    props.put("mail.smtp.auth", "true")

    val auth = new SMTPAuthenticator()
    val mailSession = Session.getDefaultInstance(props, auth)
//    mailSession.setDebug(log.isDebugEnabled)
    mailSession.setDebug(true)
    mailSession
  }

  def nullifyTransport(transport: Transport) = if (transportOpt.map(t => t == transport).getOrElse(false)) {
    log.info("setting transportOpt to None since it contains a bad state transport")
    if (transport.isConnected()) transport.close()
    transportOpt = None
  }

  def createTransport(): Transport = {
    val transport = mailSession.getTransport()
    def externalIdFromTransportEvent(e: TransportEvent) =
      ExternalId[ElectronicMail](e.getMessage().getHeader(SendgridMailProvider.KIFI_MAIL_ID)(0))

    transport.addTransportListener(new TransportListener() {
      def messageDelivered(e: TransportEvent): Unit = {
        log.info("messageDelivered: %s".format(e))
        CX.withConnection { implicit c =>
          ElectronicMail.get(externalIdFromTransportEvent(e)).sent("transport.messageDelivered").save
        }
      }
      def messageNotDelivered(e: TransportEvent): Unit = {
        mailError(externalIdFromTransportEvent(e), "transport.messageNotDelivered", transport)
      }
      def messagePartiallyDelivered(e: TransportEvent): Unit = {
        mailError(externalIdFromTransportEvent(e), "transport.messagePartiallyDelivered", transport)
      }
    })

    transport.addConnectionListener(new ConnectionListener() {
      def opened(e: ConnectionEvent)  { log.info(e) }
      def closed(e: ConnectionEvent) {
        log.info("got event %s".format(e))
        nullifyTransport(transport)
      }
      def disconnected(e: ConnectionEvent) {
        closed(e)
      }
    })

    transport.connect()
    transport
  }

  var transportOpt: Option[Transport] = Some(createTransport())

  def getLiveTransport(): Transport = transportOpt match {
    case Some(transport) =>
      if (transport.isConnected()) {
        transport
      } else {
        nullifyTransport(transport)
        transportOpt = Some(createTransport())
        transportOpt.get
      }
    case _ =>
      transportOpt = Some(createTransport())
      transportOpt.get
  }

  /**
   * Please see http://sendgrid.com/docs/API%20Reference/SMTP%20API/index.html for docs
   */
  def sendMailToSendgrid(mail: ElectronicMail): Unit = {
    val message = createMessage(mail)
    val transport = getLiveTransport()
    try {
      transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO))
      CX.withConnection { implicit c =>
        mail.sent("message sent").save
      }
      log.info("mail %s sent".format(mail.externalId))
    } catch {
      case e =>
        log.error(e)
        mailError(mail, e.toString(), transport)
    }
  }

  private def createMessage(mail: ElectronicMail) = {
    val message = new MimeMessage(mailSession)
    val multipart = new MimeMultipart("alternative")

    val part1 = new MimeBodyPart()
    part1.setText(mail.textBody.getOrElse(""))

    val part2 = new MimeBodyPart()
    part2.setContent(mail.htmlBody, ContentTypes.HTML)

    multipart.addBodyPart(part1)
    multipart.addBodyPart(part2)

    message.setHeader("X-SMTPAPI", JsObject(List("category" -> JsString("test-category"))).toString)

    message.setContent(multipart)
    message.setFrom(new InternetAddress(mail.from.address))
    message.setSubject(mail.subject)
    message.addRecipient(Message.RecipientType.TO, new InternetAddress(mail.to.address))
    message
  }

  private def mailError(mailId: ExternalId[ElectronicMail], message: String, transport: Transport): ElectronicMail = {
    val mail = CX.withConnection { implicit c =>
      ElectronicMail.get(mailId)
    }
    mailError(mail, message, transport)
  }

  private def mailError(mail: ElectronicMail, message: String, transport: Transport): ElectronicMail = {
    nullifyTransport(transport)
    val error = inject[Healthcheck].addError(HealthcheckError(callType = Healthcheck.EMAIL,
      errorMessage = Some("Can't send email from %s to %s: %s. Error message: %s".format(mail.from, mail.to, mail.subject, message))))
    log.error(error.errorMessage)
    CX.withConnection { implicit c =>
      mail.errorSending("Error: %s".format(error)).save
    }
  }
}