package com.keepit.common.mail

import com.google.inject.Inject
import com.keepit.common.logging.Logging

import com.amazonaws.services.simpleemail._
import com.amazonaws.services.simpleemail.model._

import scala.collection.JavaConversions._
import com.keepit.model.NotificationCategory

trait AmazonSimpleMailProvider {
  def sendMail(mail: ElectronicMail): Unit
}

/**
 * used only for admin emails, does not deal with persisted emails, not updating email state etc.
 * See
 * http://docs.aws.amazon.com/ses/latest/DeveloperGuide/send-email-api.html
 * http://docs.aws.amazon.com/ses/latest/DeveloperGuide/send-email-formatted.html
 */
class AmazonSimpleMailProviderImpl @Inject() (client: AmazonSimpleEmailServiceClient)
    extends MailProvider with AmazonSimpleMailProvider with Logging {

  private def isSystemEmail(mail: ElectronicMail): Boolean =
    NotificationCategory.System.all.contains(NotificationCategory.fromElectronicMailCategory(mail.category))

  private def isInternalEmailRecipients(mail: ElectronicMail): Unit = mail.to.foreach { to =>
    val in = SystemEmailAddress.ALL_EMAILS.contains(to)
    if (!in) {
      throw new Exception(s"Recipient [$to] is not part of the internal email group [${SystemEmailAddress.ALL_EMAILS.mkString(",")}}]: $mail")
    }
  }

  def sendMail(mail: ElectronicMail): Unit = {
    if (!isSystemEmail(mail)) {
      throw new Exception(s"This mail provider will not send emails unless they have a System category. Not sending: $mail")
    }
    isInternalEmailRecipients(mail)
    doSendMail(mail)
  }

  private def doSendMail(mail: ElectronicMail): Unit = {
    val request = new SendEmailRequest().withSource(mail.from.address)
    val dest = new Destination().withToAddresses(mail.to.map(_.address))
    request.setDestination(dest)

    val subjContent = new Content().withData(mail.subject)
    val msg = new model.Message().withSubject(subjContent)

    val body = new Body()
    mail.textBody foreach { text =>
      val textContent = new Content().withData(text)
      body.withText(textContent)
    }
    val htmlContent = new Content().withData(mail.htmlBody)
    body.withHtml(htmlContent)

    msg.setBody(body)

    request.setMessage(msg)

    val sendEmailResult = client.sendEmail(request)
    log.info(s"sent a simple email of id:${sendEmailResult.getMessageId} : ${mail.subject}")
  }
}
