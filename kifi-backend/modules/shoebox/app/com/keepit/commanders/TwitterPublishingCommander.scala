package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.TwitterSocialGraph
import com.keepit.model._
import com.keepit.social.SocialNetworks
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }

class TwitterPublishingCommander @Inject() (
    experimentCommander: LocalUserExperimentCommander,
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    twitterSocialGraph: TwitterSocialGraph) extends SocialPublishingCommander with Logging {

  def publishKeep(userId: Id[User], keep: Keep, library: Library): Unit = {
    require(keep.userId == userId, s"User $userId cannot publish to Twitter a keep by user ${keep.userId}")
    if (library.visibility == LibraryVisibility.PUBLISHED && hasTwitterExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.TWITTER) } match {
        case None => log.info(s"user $userId is not connected to twitter!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          val message = s"${keep.title} ${keep.url} kept to ${library.name} $libraryUrl"
          twitterSocialGraph.sendTweet(sui, message)
      }
    }
  }

  def publishLibraryMembership(userId: Id[User], library: Library): Unit = {
    if (library.visibility == LibraryVisibility.PUBLISHED && hasTwitterExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.FACEBOOK) } match {
        case None => log.info(s"user $userId is not connected to facebook!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          val message = s"following @kif library ${library.name} $libraryUrl by ${libOwner.fullName}"
          twitterSocialGraph.sendTweet(sui, message)
      }
    }
  }

  private def hasTwitterExperiment(userId: Id[User]) = {
    db.readOnlyMaster { implicit session => experimentCommander.userHasExperiment(userId, ExperimentType.FACEBOOK_POST) }
  }

}
