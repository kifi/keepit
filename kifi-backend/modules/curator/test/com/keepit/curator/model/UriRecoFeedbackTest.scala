package com.keepit.curator.model

import com.keepit.model.UriRecommendationFeedback
import org.specs2.mutable.Specification

class UriRecoFeedbackTest extends Specification {
  "UriRecoFeedbackValue" should {
    "created from recoFeedback" in {
      var fb = UriRecommendationFeedback(clicked = Some(true))
      UriRecoFeedbackValue.fromRecoFeedback(fb).get === UriRecoFeedbackValue.CLICKED

      fb = UriRecommendationFeedback(kept = Some(true))
      UriRecoFeedbackValue.fromRecoFeedback(fb).get === UriRecoFeedbackValue.KEPT

      fb = UriRecommendationFeedback(vote = Some(true))
      UriRecoFeedbackValue.fromRecoFeedback(fb).get === UriRecoFeedbackValue.LIKE

      fb = UriRecommendationFeedback(vote = Some(false))
      UriRecoFeedbackValue.fromRecoFeedback(fb).get === UriRecoFeedbackValue.DISLIKE

      fb = UriRecommendationFeedback(trashed = Some(true))
      UriRecoFeedbackValue.fromRecoFeedback(fb) === None
    }
  }
}
