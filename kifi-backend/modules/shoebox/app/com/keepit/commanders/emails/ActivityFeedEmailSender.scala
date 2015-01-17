package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.{ ProcessedImageSize, LibraryCommander, LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.curator.CuratorServiceClient
import com.keepit.curator.model.{ FullUriRecoInfo, FullLibRecoInfo, RecommendationSource, RecommendationSubSource }
import com.keepit.model.{ LibraryInvite, KeepInfo, FullLibraryInfo, ExperimentType, Library, NormalizedURI, NotificationCategory, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

trait LibraryInfoView {
  val libInfo: FullLibraryInfo
  val libraryId: Id[Library]
  val name = libInfo.name
  val description = libInfo.description
  var ownerName = libInfo.owner.fullName
  val keeps = libInfo.keeps map KeepInfoView
  val image = libInfo.image
}

case class BaseLibraryInfoView(libraryId: Id[Library], libInfo: FullLibraryInfo) extends LibraryInfoView

case class LibraryInviteInfoView(invitedByUsers: Seq[Id[User]],
    libraryId: Id[Library],
    libInfo: FullLibraryInfo) extends LibraryInfoView {
}

case class KeepInfoView(private val keepInfo: KeepInfo) {
  private val summary = keepInfo.summary
  private val imageUrl = summary flatMap (_.imageUrl)
  private val imageWidth = summary flatMap (_.imageWidth)

  val title = keepInfo.title orElse (summary flatMap (_.title)) orElse keepInfo.siteName getOrElse keepInfo.url
  val url = keepInfo.url
  val imageUrlAndWidth: Option[(String, Int)] = imageUrl flatMap { url => imageWidth map ((url, _)) }
}

case class ActivityEmailData(
    newKeepsInLibraries: Seq[LibraryInfoView],
    libraryInvites: Seq[LibraryInviteInfoView],
    libraryRecos: Seq[FullLibRecoInfo],
    uriRecos: Seq[FullUriRecoInfo]) {
}

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
    libraryCommander: LibraryCommander,
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
        newKeepsInLibraries = newKeeps map { case (libId, info) => BaseLibraryInfoView(libId, info) },
        libraryInvites = pendingLibInvites map {
          case (libId, info, invites) =>
            val inviterUserIds = invites map (_.inviterId)
            LibraryInviteInfoView(inviterUserIds, libId, info)
        },
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

    // weight of URI reco recency... must be between 0..1
    val uriRecoRecencyWeight = 1

    // max URI recommendations to include in the feed
    val maxUriRecosToDeliver = 3

    // max library recommendations to include in the feed
    val maxLibRecostoDeliver = 3

    // library recommendations to fetch from curator
    val libRecosToFetch = maxLibRecostoDeliver * 2

    // max number of user-followed libraries to display
    val maxFollowedLibraries = 3

    // max number of invited-to libraries to display
    val maxInvitedLibraries = 3

    private lazy val (followedLibraries: Seq[Library], invitedLibraries: Seq[(LibraryInvite, Library)]) = {
      val (rawUserLibs, rawInvitedLibs) = libraryCommander.getLibrariesByUser(toUserId)
      (
        rawUserLibs filterNot (_._1.isOwner) map (_._2) take maxFollowedLibraries,
        rawInvitedLibs take maxInvitedLibraries
      )
    }

    protected def createFullLibraryInfos(libraries: Seq[Library]) = {
      libraryCommander.createFullLibraryInfos(viewerUserIdOpt = Some(toUserId),
        showPublishedLibraries = true, maxKeepsShown = 10,
        maxMembersShown = 0, idealKeepImageSize = ProcessedImageSize.Large.idealSize,
        idealLibraryImageSize = ProcessedImageSize.Large.idealSize,
        libraries = libraries, withKeepTime = true)
    }

    def getNewKeepsFromFollowedLibraries(): Future[Seq[(Id[Library], FullLibraryInfo)]] = {
      createFullLibraryInfos(followedLibraries)
    }

    def getUnreadMessages(): Future[Unit] = {
      Future.successful(Unit) // TODO
    }

    def getPendingLibraryInvitations(): Future[Seq[(Id[Library], FullLibraryInfo, Seq[LibraryInvite])]] = {
      val libraries = invitedLibraries map (_._2)
      val invitesByLibraryId = invitedLibraries map (_._1) groupBy (_.libraryId)
      val fullLibraryInfosF = createFullLibraryInfos(libraries)

      fullLibraryInfosF map { fullLibraryInfos =>
        fullLibraryInfos map {
          case (libraryId, fullLibInfo) => (libraryId, fullLibInfo, invitesByLibraryId(libraryId))
        }

      }
    } recover {
      case e: Exception =>
        airbrake.notify(e)
        Seq.empty
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
        airbrake.notify(s"ActivityFeedEmail Failed to load library recommendations for userId=$toUserId", e)
        Seq.empty
    }

    def getUriRecommendations(): Future[Seq[FullUriRecoInfo]] = {
      recoCommander.topRecos(toUserId, recoSource, recoSubSource, more = false, uriRecoRecencyWeight) map { recos =>
        // TODO(josh) maybe additional filtering
        recos.take(maxUriRecosToDeliver)
      }
    } recover {
      case e: Exception =>
        airbrake.notify(s"ActivityFeedEmail Failed to load uri recommendations for userId=$toUserId", e)
        Seq.empty
    }

  }

}
