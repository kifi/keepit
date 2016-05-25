package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model.{ UserExperimentType, Keep, Library, User }
import play.api.libs.json.JsValue

trait SocialPublishingCommander {
  val db: Database
  val experimentCommander: LocalUserExperimentCommander

  def announceNewTwitterLibrary(libraryId: Id[Library]): Unit

  def hasExplicitShareExperiment(userId: Id[User]) = {
    db.readOnlyMaster { implicit session => experimentCommander.userHasExperiment(userId, UserExperimentType.EXPLICIT_SOCIAL_POSTING) }
  }
}

