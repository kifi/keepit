package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{ FullUriRecoInfo, FullLibRecoInfo, RecommendationSource, RecommendationSubSource }
import com.keepit.model.{ ExperimentType, Library, NormalizedURI, NotificationCategory, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

case class ActivityEmailData(
  newKeepsInLibraries: Seq[(Id[Library], Seq[Id[NormalizedURI]])],
  libraryRecos: Seq[FullLibRecoInfo],
  uriRecos: Seq[FullUriRecoInfo])

@ImplementedBy(classOf[ActivityFeedEmailSenderImpl])
trait ActivityFeedEmailSender {
  def apply(sendTo: Set[Id[User]]): Future[Unit]
  def apply(): Future[Unit]
}

class ActivityFeedEmailSenderImpl @Inject() (
    curator: CuratorServiceClient,
    experimentCommander: LocalUserExperimentCommander,
    emailTemplateSender: EmailTemplateSender,
    recoCommander: RecommendationsCommander,
    private val airbrake: AirbrakeNotifier) extends ActivityFeedEmailSender with Logging {

  val reactiveLock = new ReactiveLock(8)

  def apply(sendTo: Set[Id[User]]): Future[Unit] = {
    val emailsF = sendTo.toStream map prepareEmailForUser map (_ flatMap emailTemplateSender.send)
    Future.sequence(emailsF) map (_ => Unit)
  }

  def apply(): Future[Unit] = {
    apply(usersToSendEmailTo())
  }

  def usersToSendEmailTo(): Set[Id[User]] = {
    experimentCommander.getUserIdsByExperiment(ExperimentType.ACTIVITY_EMAIL)
  }

  def prepareEmailForUser(toUserId: Id[User]): Future[EmailToSend] = reactiveLock.withLockFuture {
    val feed = new UserActivityFeedHelper(toUserId)

    for {
      newKeeps <- feed.getNewKeepsFromFollowedLibraries()
      unreadMessages <- feed.getUnreadMessages()
      pendingLibInvites <- feed.getPendingLibraryInvitations()
      pendingFriendRequests <- feed.getPendingFriendRequests()
      friendsWhoFollowed <- feed.getFriendsWhoFollowedLibraries()
      uriRecos <- feed.getUriRecommendations()
      libRecos <- feed.getLibraryRecommendations()
    } yield {
      val activityData = ActivityEmailData(
        newKeepsInLibraries = newKeeps,
        libraryRecos = libRecos,
        uriRecos = uriRecos
      )

      EmailToSend(
        from = SystemEmailAddress.NOTIFICATIONS,
        to = Left(toUserId),
        subject = "Kifi Activity",
        htmlTemplate = views.html.email.black.activityFeed(toUserId, activityData),
        category = NotificationCategory.User.ACTIVITY
      )
    }
  }

  class UserActivityFeedHelper(val toUserId: Id[User]) {

    val recoSource = RecommendationSource.Email
    val recoSubSource = RecommendationSubSource.Unknown
    val uriRecoRecencyWeight = 5
    val libRecosToFetch = 10
    val maxUriRecosToDeliver = 3
    val maxLibRecostoDeliver = 3

    def getNewKeepsFromFollowedLibraries(): Future[Seq[(Id[Library], Seq[Id[NormalizedURI]])]] = {
      Future.successful(Seq.empty) // TODO
    }

    def getUnreadMessages(): Future[Unit] = {
      Future.successful(Unit) // TODO
    }

    def getPendingLibraryInvitations(): Future[Seq[(Id[User], Id[Library])]] = {
      Future.successful(Seq.empty) // TODO
    }

    def getPendingFriendRequests(): Future[Seq[Id[User]]] = {
      Future.successful(Seq.empty) // TODO
    }

    def getFriendsWhoFollowedLibraries(): Future[Seq[(Id[User], Id[Library])]] = {
      Future.successful(Seq.empty) // TODO
    }

    def getFriendsWhoCreatedLibraries(): Future[Seq[(Id[User], Id[Library])]] = {
      Future.successful(Seq.empty) // TODO
    }

    def getLibraryRecommendations(): Future[Seq[FullLibRecoInfo]] = {
      recoCommander.topPublicLibraryRecos(toUserId, libRecosToFetch, recoSource, recoSubSource) map { recos =>
        // TODO(josh) maybe additional filtering
        recos.take(maxLibRecostoDeliver)
      }
    } recover {
      case e: Exception =>
        airbrake.notify("ActivityFeedEmail Failed to load library recommendations", e)
        Seq.empty
    }

    def getUriRecommendations(): Future[Seq[FullUriRecoInfo]] = {
      recoCommander.topRecos(toUserId, recoSource, recoSubSource, more = false, uriRecoRecencyWeight) map { recos =>
        // TODO(josh) maybe additional filtering
        recos.take(maxUriRecosToDeliver)
      }
    } recover {
      case e: Exception =>
        airbrake.notify("ActivityFeedEmail Failed to load uri recommendations", e)
        Seq.empty
    }

  }

}
