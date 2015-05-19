package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.template.EmailLayout.CustomLayout
import com.keepit.common.mail.{ EmailAddress, SystemEmailAddress, ElectronicMail }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.time._
import com.keepit.model._

import scala.concurrent.Future

class GratificationEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress]) = sendToUser(userId, toAddress)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress]): Future[ElectronicMail] = {

    val viewsByLibrary = getViewsByLibrary(userId)
    val followersByLibrary = getFollowersByLibrary(userId)
    val newConnections = getNewConnections(userId)

    val emailToSend = EmailToSend(
      from = SystemEmailAddress.NOTIFICATIONS,
      to = toAddress.map(Right.apply).getOrElse(Left(userId)),
      subject = "You've been busy this week on Kifi!",
      category = NotificationCategory.User.GRATIFICATION,
      htmlTemplate = views.html.email.black.gratification(userId, viewsByLibrary, followersByLibrary, newConnections),
      textTemplate = Some(views.html.email.black.gratificationText(userId, viewsByLibrary, followersByLibrary, newConnections)),
      templateOptions = Map("layout" -> CustomLayout),
      tips = Seq.empty
    )

    emailTemplateSender.send(emailToSend)
  }

  def getViewsByLibrary(userId: Id[User]): Map[Id[Library], Int] = {
    Map.empty
  }

  def getFollowersByLibrary(userId: Id[User]): Map[Id[Library], Int] = {
    Map.empty
  }

  val NUM_WEEKS_BACK = 1

  def getNewConnections(userId: Id[User]): Seq[Id[User]] = {
    val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
    val newConnections = db.readOnlyReplica { implicit s =>
      userConnectionRepo.getConnectionsSince(userId, since)
    }
    newConnections.toSeq
  }

}

