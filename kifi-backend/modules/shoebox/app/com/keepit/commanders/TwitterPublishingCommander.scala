package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.strings._
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
        case None =>
          log.info(s"user $userId is not connected to twitter!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          val title = keep.title.getOrElse("interesting link")
          val msg = keepMessage(title, keep.url, library.name, libraryUrl)
          log.info(s"twitting about user $userId keeping $title with msg = $msg of size ${msg.size}")
          twitterSocialGraph.sendTweet(sui, msg)
      }
    }
  }

  /**
   * todo(eishay): before openning it out, should be heavily tested.
   * 140 is twitter max msg length
   * 20 is the urls after shortning
   * 20 is the message overhead " kept to via @kifi"...
   * 140 - 2 * 20 - 20 = 79
   */
  private def keepMessage(title: String, keepUrl: String, libName: String, libUrl: String): String = {
    val contentLength = title.length + libName.length
    val totalLength = 20 * 2 + 20 + title.length + libName.length
    if (20 * 2 + 20 + title.length + libName.length <= 140) {
      s"$title $keepUrl kept to $libName $libUrl"
    } else {
      val overtext = 79 - (2 * 3) - contentLength //the 3 stands for the "..."
      val maxLibName = (libName.size - overtext / 3).min(20)
      val shortLibName = libName.abbreviate(maxLibName)
      val shortTitle = if (title.size > 79 - shortLibName.size) title.abbreviate(79 - 3 - shortLibName.size) else title
      s"$shortTitle $keepUrl kept to $shortLibName $libUrl via @kifi"
    }

  }

  def publishLibraryMembership(userId: Id[User], library: Library): Unit = {
    if (library.visibility == LibraryVisibility.PUBLISHED && hasTwitterExperiment(userId)) {
      db.readOnlyMaster { implicit session => socialUserInfoRepo.getByUser(userId).find(u => u.networkType == SocialNetworks.TWITTER) } match {
        case None => log.info(s"user $userId is not connected to twitter!")
        case Some(sui) =>
          val libOwner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }
          val libraryUrl = s"""https://www.kifi.com${Library.formatLibraryPath(libOwner.username, library.slug)}"""
          val libName = library.name.abbreviate(140 - 28 - 20 - libOwner.fullName.size) //140 - text overhead - url len - lib owner size
          val message = s"following @kifi library $libName $libraryUrl by ${libOwner.fullName}"
          twitterSocialGraph.sendTweet(sui, message)
      }
    }
  }

  private def hasTwitterExperiment(userId: Id[User]) = {
    db.readOnlyMaster { implicit session => experimentCommander.userHasExperiment(userId, ExperimentType.TWEET_ALL) }
  }

}
