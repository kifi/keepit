package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.UriRecommendationRepo
import com.keepit.model.{ UriRecommendationFeedback, NormalizedURI, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

class RecommendationFeedbackCommander @Inject() (
    db: Database,
    uriRecRepo: UriRecommendationRepo,
    publicFeedGenerationCommander: PublicFeedGenerationCommander) {

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI], feedback: UriRecommendationFeedback): Future[Boolean] = {
    val future = db.readWriteAsync { implicit session =>
      uriRecRepo.updateUriRecommendationFeedback(userId, uriId, feedback)
    }
    future.onSuccess { case true => if (feedback.clicked.isDefined) publicFeedGenerationCommander.clicked(uriId) }
    future
  }

}
