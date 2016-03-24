package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.core.optionExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, LibraryPushNotificationCategory, PushNotificationExperiment }
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.LibraryNewKeep

import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class LibraryNewKeepsCommander @Inject() (
    db: Database,
    libraryMembershipRepo: LibraryMembershipRepo,
    userRepo: UserRepo,
    elizaClient: ElizaServiceClient,
    airbrake: AirbrakeNotifier,
    libPathCommander: PathCommander,
    clock: Clock,
    keepSourceCommander: KeepSourceCommander,
    implicit val publicIdConfiguration: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) {

  def notifyFollowersOfNewKeeps(library: Library, keeps: Seq[Keep]) = {
    val relevantFollowers = db.readOnlyReplica { implicit session =>
      libraryMembershipRepo.getWithLibraryId(library.id.get).filter(_.subscribedToUpdates).map(_.userId).toSet
    }

    keeps.headOption.foreach { keep =>
      sendPushNotificationsForKeep(library, keep, relevantFollowers -- keep.userId)
    }

    FutureHelpers.sequentialExec(relevantFollowers) { toBeNotified =>
      val keepsToSend = (if (toBeNotified.id == 84792 || toBeNotified.id == 134) keeps else keeps.take(1)).filterNot(_.userId.safely.contains(toBeNotified))
      elizaClient.sendNotificationEvents(keepsToSend.map(keep => LibraryNewKeep(
        Recipient.fromUser(toBeNotified),
        clock.now,
        keep.userId,
        keep.id.get,
        library.id.get
      )))
    }
  }

  private def sendPushNotificationsForKeep(library: Library, keep: Keep, toBeNotified: Set[Id[User]]): Future[Unit] = {
    val (libraryUrl, keeperOpt, sourceOpt) = db.readOnlyReplica { implicit s =>
      val libUrl = libPathCommander.libraryPage(library)
      val keeper = keep.userId.map(userRepo.get)
      val source = keepSourceCommander.getSourceAttributionForKeeps(Set(keep.id.get)).values.headOption
      (libUrl, keeper, source)
    }

    if (toBeNotified.size > 150) {
      airbrake.notify(s"Warning: Library with lots of subscribers ${toBeNotified.size}. Time to make the code better!")
    }

    val libTrunc = if (library.name.length > 30) {
      library.name.take(25) + "…"
    } else {
      library.name
    }
    val titleString = keep.title.map {
      case title if title.length > 30 => title.take(25) + "..."
      case title => title
    }.map(title => s": $title").getOrElse("")
    val message = {
      sourceOpt.collect {
        case (_, Some(kifiUser)) => s"${kifiUser.fullName} kept to $libTrunc" + titleString
        case (attr: SlackAttribution, _) =>
          val name = attr.message.username.value
          attr.message.channel.name match {
            case Some(prettyChannelName) => s"$name added to #${prettyChannelName.value}" + titleString
            case None => s"$name shared" + titleString
          }
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
        libraryUrl = libraryUrl.absolute,
        pushNotificationExperiment = PushNotificationExperiment.Experiment1,
        category = LibraryPushNotificationCategory.LibraryChanged
      )
    }
  }

}
