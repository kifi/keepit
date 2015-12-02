package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ UserExperimentType, Keep, Library, User }
import play.api.libs.json.JsValue

trait SocialPublishingCommander {
  val db: Database
  val experimentCommander: LocalUserExperimentCommander

  def publishLibraryMembership(userId: Id[User], library: Library): Unit
  def publishKeep(userId: Id[User], keep: Keep, library: Library): Unit

  def hasExplicitShareExperiment(userId: Id[User]) = {
    db.readOnlyMaster { implicit session => experimentCommander.userHasExperiment(userId, UserExperimentType.EXPLICIT_SOCIAL_POSTING) }
  }
}

case class SocialShare(twitter: Boolean, facebook: Boolean)

object SocialShare {
  val empty = SocialShare(twitter = false, facebook = false)

  def apply(json: JsValue): SocialShare = {
    val fPost = (json \ "fPost").asOpt[Boolean].contains(true)
    val tweet = (json \ "tweet").asOpt[Boolean].contains(true)
    SocialShare(twitter = tweet, facebook = fPost)
  }
}
