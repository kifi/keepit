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
import twitter4j.Status

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

class TwitterPublishingCommander @Inject() (
    val experimentCommander: LocalUserExperimentCommander,
    val db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    keepImageCommander: KeepImageCommander,
    libraryImageCommander: LibraryImageCommander,
    libPathCommander: PathCommander,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    imageStore: RoverImageStore,
    twitterMessages: TwitterMessages,
    implicit val executionContext: ExecutionContext,
    twitterSocialGraph: TwitterSocialGraph) extends Logging {

  def announceNewTwitterLibrary(libraryId: Id[Library]): Try[Status] = {
    val (library, socialInfo) = db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val socialInfo = socialUserInfoRepo.getByUser(library.ownerId).find(u => u.networkType == SocialNetworks.TWITTER).get
      (library, socialInfo)
    }
    val userHasExperiment = experimentCommander.userHasExperiment(library.ownerId, UserExperimentType.EXPLICIT_SOCIAL_POSTING)
    if (library.visibility == LibraryVisibility.PUBLISHED && userHasExperiment) {
      val libraryUrl = s"""https://www.kifi.com${libPathCommander.getPathForLibrary(library)}"""
      val handle = socialInfo.profileUrl.map(twitterMessages.parseHandleFromUrl)
      val message = s"@$handle Your #twitterdeepsearch fully searchable @kifi library is ready! $libraryUrl?&kcid=na-kp_social_jr-twitter-dsw"
      twitterSocialGraph.sendTweet(socialInfo, None, message)
    } else Failure(new Exception(s"library is private"))
  }

}
