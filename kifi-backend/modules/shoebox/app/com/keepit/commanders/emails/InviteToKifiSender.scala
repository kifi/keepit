package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ PostOffice, ElectronicMail, SystemEmailAddress, EmailAddress }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.mail.template.helpers.{ baseUrl, htmlUrl }
import com.keepit.common.store.S3ImageStore
import com.keepit.controllers.website.routes
import com.keepit.heimdal.HeimdalContextBuilder
import com.keepit.model.{ UserRepo, Invitation, UserEmailAddressRepo, User, NotificationCategory }
import play.twirl.api.Html

import scala.concurrent.Future

trait InviteEmailData {
  def abCode: String
  def subject: String
  def htmlTemplate: Html
  def textTemplate: Option[Html]
}

class InviteToKifiSender @Inject() (
    db: Database,
    emailAddressRepo: UserEmailAddressRepo,
    emailTemplateSender: EmailTemplateSender,
    userRepo: UserRepo,
    s3ImageStore: S3ImageStore,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(toAddress: EmailAddress, fromUserId: Id[User], inviteId: ExternalId[Invitation]): Future[ElectronicMail] = {
    val acceptUrl: Html = htmlUrl(baseUrl.body + routes.InviteController.acceptInvite(inviteId).url + "?", "acceptInviteBtn")

    // NOTE changes to the email that are to be used for AB-testing should set a different
    // abCode and/or add additional auxiliaryData to EmailToSend
    val emailData = new InviteEmailData {
      val abCode = "0"
      val subject = "Join me on kifi"
      val htmlTemplate = views.html.email.black.inviteToKifi(fromUserId, acceptUrl)
      val textTemplate = Some(views.html.email.black.inviteToKifiText(fromUserId, acceptUrl))
    }

    val emailToSend = buildEmailToSend(toAddress, fromUserId, emailData)
    log.info(s"sending to address=$toAddress subject=${emailData.subject} from userId=$fromUserId")
    emailTemplateSender.send(emailToSend)
  }

  private def buildEmailToSend(toAddress: EmailAddress, fromUserId: Id[User], emailData: InviteEmailData): EmailToSend = {
    val fromAddress = db.readOnlyReplica(3) { emailAddressRepo.getByUser(fromUserId)(_) }

    EmailToSend(
      fromName = Some(Left(fromUserId)),
      from = SystemEmailAddress.INVITATION,
      subject = emailData.subject,
      to = Right(toAddress),
      category = NotificationCategory.NonUser.INVITATION,
      htmlTemplate = emailData.htmlTemplate,
      textTemplate = emailData.textTemplate,
      extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> fromAddress.address)),
      auxiliaryData = {
        val builder = new HeimdalContextBuilder
        builder += ("emailAbCode", emailData.abCode)
        Some(builder.build)
      }
    )
  }

}
