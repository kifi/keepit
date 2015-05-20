package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
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

class GratificationEmailSender @Inject() (
    emailTemplateSender: EmailTemplateSender,
    gratificationCommander: GratificationCommander,
    db: Database,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  val NUM_WEEKS_BACK = 1
  val EXPERIMENT_DEPLOY = true
  val MIN_FOLLOWERS = 1
  val MIN_VIEWS = 5
  val MIN_CONNECTIONS = 1

  def apply(userId: Id[User], toAddress: Option[EmailAddress]) = sendToUser(userId, toAddress)

  def sendToUser(userId: Id[User], toAddress: Option[EmailAddress]): Future[ElectronicMail] = {

    val fViewsByLibrary = gratificationCommander.getLibraryViewData(userId)
    val followersByLibrary = gratificationCommander.getLibraryFollowerCounts(userId)
    val newConnections = getNewConnections(userId)

    fViewsByLibrary.flatMap { viewsByLibrary =>
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
  }

  def getNewConnections(userId: Id[User]): Seq[Id[User]] = {
    val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
    val newConnections = db.readOnlyReplica { implicit s =>
      userConnectionRepo.getConnectionsSince(userId, since)
    }
    newConnections.toSeq
  }

  def usersToSendEmailTo(): Either[Seq[Id[User]], Seq[Future[Id[User]]]] = {
    val userIds: Seq[Id[User]] = db.readOnlyReplica { implicit session => userRepo.getAllIds() }.toSeq

    if (EXPERIMENT_DEPLOY) {
      Left(userIds.filter { id =>
        localUserExperimentCommander.userHasExperiment(id, ExperimentType.GRATIFICATION)
      })
    } else {
      val result: Seq[Future[Id[User]]] = userIds.map { id =>
        val newConnections = getNewConnections(id)
        val followersByLibrary = gratificationCommander.getLibraryFollowerCounts(id)
        val fViewsByLibrary = gratificationCommander.getLibraryViewData(id)
        fViewsByLibrary.map { viewsByLib => if (newConnections.length >= MIN_CONNECTIONS || followersByLibrary.totalCount >= MIN_FOLLOWERS || viewsByLib.totalCount >= MIN_VIEWS) id else Id[User](-1) }
      }
      Right(result)
    }
  }
}

