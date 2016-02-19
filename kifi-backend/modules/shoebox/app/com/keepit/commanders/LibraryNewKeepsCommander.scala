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
import com.keepit.social.{ BasicAuthor, BasicUser }
import play.api.libs.json.Json
import scala.concurrent.Future
import com.google.inject.Singleton

import scala.concurrent.ExecutionContext

@Singleton
class LibraryNewKeepsCommander @Inject() (
    db: Database,
    libraryMembershipRepo: LibraryMembershipRepo,
    userRepo: UserRepo,
    elizaClient: ElizaServiceClient,
    airbrake: AirbrakeNotifier,
    libPathCommander: PathCommander,
    keepSourceCommander: KeepSourceCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) {

  def notifyFollowersOfNewKeeps(library: Library, keep: Keep) = {
    val (relevantFollowers, usersById) = db.readOnlyReplica { implicit session =>
      val relevantFollowers: Set[Id[User]] = libraryMembershipRepo.getWithLibraryId(library.id.get).filter(_.subscribedToUpdates).map(_.userId).toSet
      val usersById = userRepo.getUsers(keep.userId.toSeq :+ library.ownerId)
      (relevantFollowers, usersById)
    }

    val toBeNotified = relevantFollowers -- keep.userId
    if (toBeNotified.nonEmpty) {
      val keeperOpt = keep.userId.flatMap(usersById.get)
      val sourceOpt = db.readOnlyReplica { implicit s =>
        keepSourceCommander.getSourceAttributionForKeeps(Set(keep.id.get)).values.headOption
      }

      if (toBeNotified.size > 150) {
        airbrake.notify(s"Warning: Library with lots of subscribers ${toBeNotified.size}. Time to make the code better!")
      }

      val libTrunc = if (library.name.length > 30) { library.name.take(25) + "…" } else { library.name }
      val titleString = keep.title.map {
        case title if title.length > 30 => title.take(25) + "..."
        case title => title
      }.map(title => s": $title").getOrElse("")
      val message = {
        sourceOpt.collect {
          case (_, Some(kifiUser)) => s"${kifiUser.fullName} kept to $libTrunc" + titleString
          case (attr: SlackAttribution, _) =>
            val name = attr.message.username.value
            s"$name added to #${attr.message.channel.name.value}" + titleString
        } getOrElse {
          val keepAdded = keeperOpt match {
            case Some(bu) => s"${bu.fullName} kept to"
            case None => "A keep was added to"
          }
          s"$keepAdded to $libTrunc" + titleString
        }
      }

      FutureHelpers.sequentialExec(toBeNotified) { userId =>
        elizaClient.sendLibraryPushNotification(
          userId,
          message = message,
          libraryId = library.id.get,
          libraryUrl = db.readOnlyMaster { implicit s => libPathCommander.libraryPage(library).absolute },
          pushNotificationExperiment = PushNotificationExperiment.Experiment1,
          category = LibraryPushNotificationCategory.LibraryChanged
        )
        keeperOpt.map { keeper =>
          elizaClient.sendNotificationEvent(LibraryNewKeep(
            Recipient(userId),
            currentDateTime,
            keeper.id.get,
            keep.id.get,
            library.id.get
          ))
        }.getOrElse(Future.successful(Unit))
      }
    } else Future.successful((): Unit)
  }

}
