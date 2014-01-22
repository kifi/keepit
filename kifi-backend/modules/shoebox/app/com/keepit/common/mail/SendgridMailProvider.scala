package com.keepit.common.mail

import com.keepit.common.logging.Logging
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.strings._

import java.util.Properties
import javax.mail._
import javax.mail.internet._
import javax.mail.event._
import javax.mail.{Authenticator, PasswordAuthentication}

import com.google.inject.{Inject, Singleton}


import play.api.Play
import play.api.Play.current
import play.api.http.ContentTypes
import com.keepit.heimdal._
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsObject

@Singleton
class SendgridMailProvider @Inject() (
    db: Database,
    mailRepo: ElectronicMailRepo,
    airbrake: AirbrakeNotifier,
    heimdal: HeimdalServiceClient)
  extends MailProvider with Logging {

  private class SMTPAuthenticator extends Authenticator {
    override def getPasswordAuthentication(): PasswordAuthentication = {
      val username = "fortytwo"//load from conf
      val password = "keepemailsrunning"
      return new PasswordAuthentication(username, password)
    }
  }

  private lazy val mailSession: Session = {
    val props = new Properties()
    props.put("mail.transport.protocol", "smtp")
    props.put("mail.smtp.host", "smtp.sendgrid.net")
    props.put("mail.smtp.port", 587.asInstanceOf[AnyRef])
    props.put("mail.smtp.auth", "true")

    val auth = new SMTPAuthenticator()
    val mailSession = Session.getInstance(props, auth)
    mailSession.setDebug(log.isDebugEnabled)
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
      ExternalId[ElectronicMail](e.getMessage().getHeader(MailProvider.KIFI_MAIL_ID)(0))

    transport.addTransportListener(new TransportListener() {
      def messageDelivered(e: TransportEvent): Unit = {
        log.info("messageDelivered: %s".format(e))
      }
      def messageNotDelivered(e: TransportEvent): Unit = {
        mailError(externalIdFromTransportEvent(e), "transport.messageNotDelivered", transport)
      }
      def messagePartiallyDelivered(e: TransportEvent): Unit = {
        mailError(externalIdFromTransportEvent(e), "transport.messagePartiallyDelivered", transport)
      }
    })

    transport.addConnectionListener(new ConnectionListener() {
      def opened(e: ConnectionEvent)  { log.info(e.toString) }
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

  private var transportOpt: Option[Transport] = None

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
  def sendMail(mail: ElectronicMail) {
    if (mail.isReadyToSend) {
      val checkAgain = db.readOnly(mailRepo.getOpt(mail.id.get)(_)).map(_.isReadyToSend).getOrElse(false)
      if(checkAgain) {
        val message = try {
          createMessage(mail)
        } catch {
          case t: Throwable =>
            db.readWrite { implicit s =>
              mailRepo.save(mail.error(t.toString))
            }
            throw t
        }
        val transport = getLiveTransport()
        try {
          transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO))
          val messageId = message.getHeader(MailProvider.MESSAGE_ID)(0).trim
          log.info("mail ${mail.externalId} sent with new Message-ID: $messageId")
          db.readWrite { implicit s =>
            mailRepo.save(mail.sent("message sent", ElectronicMailMessageId.fromEmailHeader(messageId)))
          }
        } catch {
          case e: Throwable =>
            log.error(e.toString)
            mailError(mail, e.toString(), transport)
        }
      }
    }
  }

  private def createMessage(mail: ElectronicMail) = {
    val message = new MimeMessage(mailSession)
    message.setHeader(MailProvider.KIFI_MAIL_ID, mail.externalId.id)
    for (id <- mail.inReplyTo) {
      message.setHeader("In-Reply-To", id.toEmailHeader)
      message.setHeader("References", id.toEmailHeader)
    }

    mail.extraHeaders.foreach{ headers =>
      PostOffice.Headers.ALL.foreach{ header =>
        headers.get(header).foreach{ value =>
          message.setHeader(header, value)
        }
      }
    }

    val multipart = new MimeMultipart("alternative")

    val part1 = new MimeBodyPart()
    part1.setText(mail.textBody.map(_.value).getOrElse(""))

    val part2 = new MimeBodyPart()
    part2.setContent(mail.htmlBody.value, ContentTypes.HTML)

    multipart.addBodyPart(part1)
    multipart.addBodyPart(part2)

    val uniqueArgs = "unique_args" -> JsObject(List("mail_id" -> JsString(mail.externalId.id)))
    message.setHeader("X-SMTPAPI", JsObject(List("category" -> JsString(mail.category.category), uniqueArgs)).toString)

    message.setContent(multipart)

    val fromName: String = mail.fromName.getOrElse(mail.from.address)
    message.setFrom(new InternetAddress(mail.from.address, fromName, UTF8))

    val recipientAddr: Array[Address] = Play.isProd match {
      case true => (mail.to map { e => new InternetAddress(e.address) }).toArray
      case false => Array(new InternetAddress(fortyTwoUsername + "+test_to@42go.com"))
    }
    message.setSubject(mail.subject)
    message.addRecipients(Message.RecipientType.TO, recipientAddr)
    if (!mail.cc.isEmpty) {
      val recipientCCAddr: Array[Address] = Play.isProd match {
        case true => (mail.cc map { e => new InternetAddress(e.address) }).toArray
        case false => Array(new InternetAddress(fortyTwoUsername + "+test_cc@42go.com"))
      }
      message.addRecipients(Message.RecipientType.CC, recipientCCAddr)
    }
    message
  }

  private def fortyTwoUsername =
    Play.configuration.getString("fortytwo.username") getOrElse System.getProperty("user.name")

  private def mailError(mailId: ExternalId[ElectronicMail], message: String, transport: Transport): ElectronicMail = {
    val mail = db.readOnly {implicit s =>
      mailRepo.get(mailId)
    }
    mailError(mail, message, transport)
  }

  private def mailError(mail: ElectronicMail, message: String, transport: Transport): ElectronicMail = {
    nullifyTransport(transport)
    val error = airbrake.notify(
      s"Can't send email from ${mail.from} to ${mail.to}: ${mail.subject}. Error message: $message")
    db.readWrite { implicit s =>
      mailRepo.save(mail.errorSending(s"Error: $error"))
    }
  }
}
