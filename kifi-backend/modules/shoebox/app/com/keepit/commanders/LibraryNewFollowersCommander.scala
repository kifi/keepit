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
import com.keepit.notify.NotificationEventSender
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.LibraryNewKeep
import com.keepit.social.BasicUser
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext

class LibraryNewFollowersCommander @Inject() (
    db: Database,
    libraryMembershipRepo: LibraryMembershipRepo,
    userRepo: UserRepo,
    libraryImageCommander: LibraryImageCommander,
    elizaClient: ElizaServiceClient,
    notificationEventSender: NotificationEventSender,
    airbrake: AirbrakeNotifier,
    s3ImageStore: S3ImageStore,
    libPathCommander: PathCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) {

  def notifyFollowersOfNewKeeps(library: Library, newKeeps: Keep*): Unit = {
    newKeeps.foreach { newKeep =>
      // old way, should be safe to remove
      if (newKeep.libraryId.get != library.id.get) { throw new IllegalArgumentException(s"Keep ${newKeep.id.get} does not belong to expected library ${library.id.get}") }
    }
    val (relevantFollowers, usersById) = db.readOnlyReplica { implicit session =>
      val relevantFollowers: Set[Id[User]] = libraryMembershipRepo.getWithLibraryId(library.id.get).filter(_.subscribedToUpdates).map(_.userId).toSet
      val usersById = userRepo.getUsers(newKeeps.map(_.userId) :+ library.ownerId)
      (relevantFollowers, usersById)
    }
    val libImageOpt = libraryImageCommander.getBestImageForLibrary(library.id.get, ProcessedImageSize.Medium.idealSize)
    newKeeps.foreach { newKeep =>
      val toBeNotified = relevantFollowers - newKeep.userId
      if (toBeNotified.nonEmpty) {
        val keeper = usersById(newKeep.userId)
        val basicKeeper = BasicUser.fromUser(keeper)
        elizaClient.sendGlobalNotification(
          userIds = toBeNotified,
          title = s"New Keep in ${library.name}",
          body = s"${keeper.firstName} has just kept ${newKeep.title.getOrElse("a new item")}",
          linkText = "Go to Page",
          linkUrl = newKeep.url,
          imageUrl = s3ImageStore.avatarUrlByUser(keeper),
          sticky = false,
          category = NotificationCategory.User.NEW_KEEP,
          extra = Some(Json.obj(
            "keeper" -> basicKeeper,
            "library" -> Json.toJson(LibraryNotificationInfoBuilder.fromLibraryAndOwner(library, libImageOpt, basicKeeper)),
            "keep" -> Json.obj(
              "id" -> newKeep.externalId,
              "url" -> newKeep.url
            )
          ))
        ).foreach { _ =>
            if (toBeNotified.size > 100) {
              airbrake.notify("Warning: Library with lots of subscribers. Time to make the code better!")
            }
            val libTrunc = if (library.name.length > 30) { library.name.take(30) + "…" } else { library.name }
            val message = newKeep.title match {
              case Some(title) =>
                val trunc = if (title.length > 30) { title.take(30) + "…" } else { title }
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
            }
          }
        toBeNotified foreach { userId =>
          notificationEventSender.send(LibraryNewKeep(
            Recipient(userId),
            currentDateTime,
            keeper.id.get,
            newKeep.id.get,
            library.id.get
          ))
        }
      }
    }
  }

}
