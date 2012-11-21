package com.keepit.common.mail

import com.keepit.common.logging.Logging
import com.keepit.common.db.CX
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.inject._

import java.util.Properties
import javax.mail._
import javax.mail.internet._
import javax.mail.event._
import javax.mail.{Authenticator, PasswordAuthentication}

import com.google.inject.Inject

import play.api.Play.current
import play.api.http.ContentTypes

class SendgridMailProvider @Inject() () extends Logging {

  private class SMTPAuthenticator extends Authenticator {
    override def getPasswordAuthentication(): PasswordAuthentication = {
      val username = "fortytwo"//load from conf
      val password = "keepemailsrunning"
      return new PasswordAuthentication(username, password)
    }
  }

  /**
   * Please see http://sendgrid.com/docs/API%20Reference/SMTP%20API/index.html for docs
   */
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
    val error = inject[Healthcheck].addError(HealthcheckError(callType = Healthcheck.EMAIL,
      errorMessage = Some("Can't send email from %s to %s: %s. Error message: %s".format(mail.from, mail.to, mail.subject, message))))
    CX.withConnection { implicit c =>
      mail.errorSending("Error: %s".format(error)).save
    }
  }
}