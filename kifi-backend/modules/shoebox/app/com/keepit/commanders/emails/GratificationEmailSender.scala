package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.model.{ User, NotificationCategory }

import play.api.db

import scala.concurrent.Future

class GratificationEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    db: Database,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress]) = sendToUser(userId, toAddress)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress]) = {
    val gratificationData = getGratificationData(userId)

    val emailToSend = EmailToSend(
      from = SystemEmailAddress.NOTIFICATIONS,
      to = toAddress.map(Right.apply).getOrElse(Left(userId)),
      subject = " You've been busy this week on Kifi!",
      category = NotificationCategory.User.GRATIFICATION,
      htmlTemplate = views.html.email.black.gratification(userId),
      textTemplate = Some(views.html.email.gratificationText(userId)),
      templateOptions = Map("layout" -> CustomLayout),
      tips = Seq.empty
    )
  }

  def getGratificationData(userId: Id[User]): Unit = {
  }

}

