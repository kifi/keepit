package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.TwitterSocialGraph
import com.keepit.common.store.{ RoverImageStore, ImageSize }
import com.keepit.common.strings._
import com.keepit.model._
import com.keepit.social.SocialNetworks
import play.api.libs.Files.TemporaryFile

import scala.concurrent.{ ExecutionContext, Future }

class TwitterPublishingCommander @Inject() (
    val experimentCommander: LocalUserExperimentCommander,
    val db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    keepImageCommander: KeepImageCommander,
    libraryImageCommander: LibraryImageCommander,
    libPathCommander: PathCommander,
    userRepo: UserRepo,
    imageStore: RoverImageStore,
    twitterMessages: TwitterMessages,
    implicit val executionContext: ExecutionContext,
    twitterSocialGraph: TwitterSocialGraph) extends SocialPublishingCommander with Logging {

  def announceNewTwitterLibrary(libraryId: Id[Library]): Unit = ???

  private def twitterHandle(libOwner: Id[User]): Option[String] = {
    val suiOpt = db.readOnlyMaster { implicit s =>
      socialUserInfoRepo.getByUser(libOwner).find(_.networkType == SocialNetworks.TWITTER)
    }
    suiOpt.flatMap(_.profileUrl).map(twitterMessages.parseHandleFromUrl)
  }

  def publishLibraryMembership(userId: Id[User], library: Library): Unit = {
    if (library.visibility == LibraryVisibility.PUBLISHED && hasExplicitShareExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.TWITTER) } match {
        case None => log.info(s"user $userId is not connected to twitter!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${libPathCommander.getPathForLibrary(library)}"""
          val libName = library.name.abbreviate(140 - 28 - 20 - libOwner.fullName.size) //140 - text overhead - url len - lib owner size
          val name = twitterHandle(libOwner.id.get).getOrElse(libOwner.fullName.trim)
          val message = s"following @kifi library ${libName.trim} $libraryUrl by $name"
          val imageOpt: Option[Future[TemporaryFile]] = db.readOnlyMaster(implicit s => libraryImageCommander.getBestImageForLibrary(library.id.get, ImageSize(1024, 512))) map { libImage =>
            imageStore.get(libImage.imagePath)
          }
          imageOpt match {
            case None => twitterSocialGraph.sendTweet(sui, None, message)
            case Some(imageFuture) => imageFuture.map { imageFile => twitterSocialGraph.sendTweet(sui, Some(imageFile.file), message) }
          }
      }
    }
  }

}
