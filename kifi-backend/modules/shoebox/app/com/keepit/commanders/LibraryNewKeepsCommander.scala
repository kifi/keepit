package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, LibraryPushNotificationCategory, PushNotificationExperiment }
import com.keepit.model._
import com.keepit.notify.NotificationInfoModel
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.LibraryNewKeep
import com.keepit.social.BasicUser
import play.api.libs.json.Json
import scala.concurrent.Future
import com.google.inject.Singleton

import scala.concurrent.ExecutionContext

@Singleton
class LibraryNewKeepsCommander @Inject() (
    db: Database,
    libraryMembershipRepo: LibraryMembershipRepo,
    userRepo: UserRepo,
    libraryImageCommander: LibraryImageCommander,
    elizaClient: ElizaServiceClient,
    airbrake: AirbrakeNotifier,
    s3ImageStore: S3ImageStore,
    libPathCommander: PathCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) {

  def notifyFollowersOfNewKeeps(library: Library, keep: Keep) = {
    val (relevantFollowers, usersById) = db.readOnlyReplica { implicit session =>
      val relevantFollowers: Set[Id[User]] = libraryMembershipRepo.getWithLibraryId(library.id.get).filter(_.subscribedToUpdates).map(_.userId).toSet
      val usersById = userRepo.getUsers(keep.userId :: library.ownerId :: Nil)
      (relevantFollowers, usersById)
    }

    val toBeNotified = relevantFollowers - keep.userId
    if (toBeNotified.nonEmpty) {
      val keeper = usersById(keep.userId)
      if (toBeNotified.size > 150) {
        airbrake.notify(s"Warning: Library with lots of subscribers ${toBeNotified.size}. Time to make the code better!")
      }
      val libTrunc = if (library.name.length > 30) { library.name.take(25) + "…" } else { library.name }
      val message = keep.title match {
        case Some(title) =>
          val trunc = if (title.length > 30) { title.take(25) + "…" } else { title }
          s"“$trunc” added to $libTrunc"
        case None =>
          s"New keep added to $libTrunc"
      }
      FutureHelpers.sequentialExec(toBeNotified) { userId =>
        elizaClient.sendLibraryPushNotification(
          userId,
          message = message,
          libraryId = library.id.get,
          libraryUrl = "https://www.kifi.com" + libPathCommander.getPathForLibrary(library),
          pushNotificationExperiment = PushNotificationExperiment.Experiment1,
          category = LibraryPushNotificationCategory.LibraryChanged
        )
        elizaClient.sendNotificationEvent(LibraryNewKeep(
          Recipient(userId),
          currentDateTime,
          keeper.id.get,
          keep.id.get,
          library.id.get
        ))
      }
    } else Future.successful((): Unit)
  }

}
