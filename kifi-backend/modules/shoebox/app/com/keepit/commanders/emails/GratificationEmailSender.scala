package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.model._

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

class GratificationEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    gratificationCommander: GratificationCommander,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  val sendEmailLock = new ReactiveLock(5)

  def apply(userId: Id[User], toAddress: Option[EmailAddress]) = sendToUser(userId, toAddress)

  def sendToUsersWithData(gratDatas: Seq[GratificationData], toAddress: Option[EmailAddress] = None): Seq[Future[ElectronicMail]] = {
    gratDatas.filter(_.isEligible).map { gratData =>
      sendEmailLock.withLockFuture { emailTemplateSender.send(emailToSend(gratData, toAddress)) }
    }
  }

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress] = None): Future[Option[ElectronicMail]] = {

    val fGratData = gratificationCommander.getGratData(userId)
    val thresh = 2

    fGratData.flatMap { gratData =>
      val hasEnoughData = gratData.isEligible && (
        gratData.keepViews.totalCount > thresh ||
        gratData.libraryFollows.totalCount > thresh ||
        gratData.libraryViews.totalCount > thresh ||
        gratData.rekeeps.totalCount > thresh
      )
      if (hasEnoughData) {
        val email = emailToSend(gratData, toAddress)
        if (email.textTemplate.exists(_.toString().length > 160)) {
          emailTemplateSender.send(emailToSend(gratData, toAddress)).map(Some(_))
        } else Future.successful(None)
      } else Future.successful(None)
    }
  }

  def emailToSend(gratData: GratificationData, toAddress: Option[EmailAddress]): EmailToSend = {
    EmailToSend(
      from = SystemEmailAddress.NOTIFICATIONS,
      fromName = Some(Right("The Kifi Team")),
      to = toAddress.map(Right.apply).getOrElse(Left(gratData.userId)),
      subject = "People have been viewing your content on Kifi!",
      category = NotificationCategory.User.GRATIFICATION_EMAIL,
      htmlTemplate = views.html.email.black.gratification(gratData.userId, gratData.libraryFollows, gratData.libraryViews, gratData.keepViews, gratData.rekeeps),
      textTemplate = Some(views.html.email.black.gratificationText(gratData.userId, gratData.libraryFollows, gratData.libraryViews, gratData.keepViews, gratData.rekeeps)),
      templateOptions = Map("layout" -> CustomLayout),
      tips = Seq.empty)
  }
}

