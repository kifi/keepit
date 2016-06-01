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
    val (user, library, kifiTwitterAccount, userTwitterAccount) = db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val kifiTwitterAccount = socialUserInfoRepo.get(Id[SocialUserInfo](6934770)) //kifi sui id. i.e. this is kifi's twitter account and credentials
      val userTwitterAccount = socialUserInfoRepo.getByUser(library.ownerId).find(u => u.networkType == SocialNetworks.TWITTER).get
      val user = userRepo.get(library.ownerId)
      (user, library, kifiTwitterAccount, userTwitterAccount)
    }
    if (library.visibility == LibraryVisibility.PUBLISHED && user.state == UserStates.ACTIVE) {
      val libraryUrl = s"""https://www.kifi.com${libPathCommander.getPathForLibrary(library)}"""
      userTwitterAccount.profileUrl.map(twitterMessages.parseHandleFromUrl) match {
        case Some(handle) =>
          val message = s"$handle your #twitterdeepsearch fully searchable @kifi library is ready! $libraryUrl?kcid=na-kp_social_jr-twitter-dsw"
          twitterSocialGraph.sendTweet(kifiTwitterAccount, None, message)
        case None => Failure(new Exception(s"can't get user profile handle for $userTwitterAccount"))
      }
    } else Failure(new Exception(s"library is private $library ; or user is not active: $user"))
  }

}
