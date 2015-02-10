package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.store.{ KeepImageStore, LibraryImageStore, ImageSize }
import com.keepit.common.strings._
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.TwitterSocialGraph
import com.keepit.model._
import com.keepit.social.SocialNetworks
import play.api.libs.Files.TemporaryFile
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class TwitterPublishingCommander @Inject() (
    experimentCommander: LocalUserExperimentCommander,
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    keepImageCommander: KeepImageCommander,
    libraryImageCommander: LibraryImageCommander,
    userRepo: UserRepo,
    libraryImageStore: LibraryImageStore,
    twitterMessages: TwitterMessages,
    keepImageStore: KeepImageStore,
    twitterSocialGraph: TwitterSocialGraph) extends SocialPublishingCommander with Logging {

  def publishKeep(userId: Id[User], keep: Keep, library: Library): Unit = {
    require(keep.userId == userId, s"User $userId cannot publish to Twitter a keep by user ${keep.userId}")
    if (library.visibility == LibraryVisibility.PUBLISHED && hasTwitterExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.TWITTER) } match {
        case None =>
          log.info(s"user $userId is not connected to twitter!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session =>
            userRepo.get(library.ownerId)
          }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          val title = keep.title.getOrElse("interesting link")
          val msg = twitterMessages.keepMessage(title.trim, keep.url.trim, library.name.trim, libraryUrl.trim)
          log.info(s"twitting about user $userId keeping $title with msg = $msg of size ${msg.size}")
          val imageOpt: Option[Future[TemporaryFile]] = keepImageCommander.getBestImageForKeep(keep.id.get, ImageSize(1024, 512)).flatten.map { keepImage =>
            keepImageStore.get(keepImage.imagePath)
          } orElse {
            libraryImageCommander.getBestImageForLibrary(library.id.get, ImageSize(1024, 512)) map { libImage =>
              libraryImageStore.get(libImage.imagePath)
            }
          }
          imageOpt match {
            case None => twitterSocialGraph.sendTweet(sui, msg)
            case Some(imageFuture) => imageFuture.map { imageFile => twitterSocialGraph.sendImage(sui, imageFile.file, msg) }
          }
      }
    }
  }

  private def twitterHandle(libOwner: Id[User]): Option[String] = {
    val suiOpt = db.readOnlyMaster { implicit s =>
      socialUserInfoRepo.getByUser(libOwner).find(_.networkType == SocialNetworks.TWITTER)
    }
    suiOpt.flatMap(_.profileUrl).map(twitterMessages.parseHandleFromUrl)
  }

  def publishLibraryMembership(userId: Id[User], library: Library): Unit = {
    if (library.visibility == LibraryVisibility.PUBLISHED && hasTwitterExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.TWITTER) } match {
        case None => log.info(s"user $userId is not connected to twitter!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          val libName = library.name.abbreviate(140 - 28 - 20 - libOwner.fullName.size) //140 - text overhead - url len - lib owner size
          val name = twitterHandle(libOwner.id.get).getOrElse(libOwner.fullName.trim)
          val message = s"following @kifi library ${libName.trim} $libraryUrl by $name"
          val imageOpt: Option[Future[TemporaryFile]] = libraryImageCommander.getBestImageForLibrary(library.id.get, ImageSize(1024, 512)) map { libImage =>
            libraryImageStore.get(libImage.imagePath)
          }
          imageOpt match {
            case None => twitterSocialGraph.sendTweet(sui, message)
            case Some(imageFuture) => imageFuture.map { imageFile => twitterSocialGraph.sendImage(sui, imageFile.file, message) }
          }
      }
    }
  }

  private def hasTwitterExperiment(userId: Id[User]) = {
    db.readOnlyMaster { implicit session => experimentCommander.userHasExperiment(userId, ExperimentType.TWEET_ALL) }
  }

}
