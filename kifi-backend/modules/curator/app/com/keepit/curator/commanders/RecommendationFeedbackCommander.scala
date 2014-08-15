package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.UriRecommendationRepo
import com.keepit.model.{ UriRecommendationUserInteraction, UriRecommendationFeedback, NormalizedURI, User }

import scala.concurrent.Future

class RecommendationFeedbackCommander @Inject() (
    uriRecRepo: UriRecommendationRepo,
    db: Database) {

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    db.readWriteAsync { implicit session =>
      uriRecRepo.updateUriRecommendationFeedback(userId, uriId, feedback)
    }
  }

  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI], interaction: UriRecommendationUserInteraction): Future[Boolean] = {
    db.readWriteAsync { implicit session =>
      uriRecRepo.updateUriRecommendationUserInteraction(userId, uriId, interaction)
    }
  }
}
