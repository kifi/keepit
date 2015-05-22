package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
import com.keepit.commanders.emails.GratificationEmailSender.SortedLibraryCountData
import com.keepit.common.akka.SafeFuture
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
import play.api.libs.concurrent.Execution.Implicits._

object GratificationEmailSender {

  case class SortedLibraryCountData(totalCount: Int, countByLibrary: List[(Id[Library], Int)]) // represents the total and per-library view counts for a single user
}

class GratificationEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    gratificationCommander: GratificationCommander,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  def apply(userId: Id[User], toAddress: Option[EmailAddress]) = sendToUser(userId, toAddress)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress]): Future[ElectronicMail] = {

    val fViewsByLibrary: Future[LibraryCountData] = gratificationCommander.getLibraryViewData(userId)
    val followersByLibrary: LibraryCountData = gratificationCommander.getLibraryFollowerCounts(userId)
    val newConnections: Seq[Id[User]] = gratificationCommander.getNewConnections(userId)

    fViewsByLibrary.flatMap { viewsByLibrary: LibraryCountData =>
      val sortedViewsByLibrary = SortedLibraryCountData(viewsByLibrary.totalCount, viewsByLibrary.countByLibrary.toList.sortWith(_._2 > _._2))
      val sortedFollowersByLibrary = SortedLibraryCountData(followersByLibrary.totalCount, followersByLibrary.countByLibrary.toList.sortWith(_._2 > _._2))
      val emailToSend = EmailToSend(
        from = SystemEmailAddress.CAM,
        fromName = Some(Right("Cam")),
        to = toAddress.map(Right.apply).getOrElse(Left(userId)),
        subject = "You've been busy this week on Kifi!",
        category = NotificationCategory.User.GRATIFICATION_EMAIL,
        htmlTemplate = views.html.email.black.gratification(userId, sortedViewsByLibrary, sortedFollowersByLibrary, newConnections),
        textTemplate = Some(views.html.email.black.gratificationText(userId, sortedViewsByLibrary, sortedFollowersByLibrary, newConnections)),
        templateOptions = Map("layout" -> CustomLayout),
        tips = Seq.empty
      )
      emailTemplateSender.send(emailToSend)
    }
  }
}

