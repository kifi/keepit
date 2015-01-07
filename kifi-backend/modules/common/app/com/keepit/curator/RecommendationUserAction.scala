package com.keepit.curator

case class RecommendationUserAction(value: String)

object RecommendationUserAction {
  object Delivered extends RecommendationUserAction("delivered")
  object Clicked extends RecommendationUserAction("clicked")
  object Kept extends RecommendationUserAction("kept")
  object Followed extends RecommendationUserAction("followed")
  object MarkedGood extends RecommendationUserAction("marked_good")
  object MarkedBad extends RecommendationUserAction("marked_bad")
  object Trashed extends RecommendationUserAction("trashed")
  object ImprovementSuggested extends RecommendationUserAction("improvement_suggested")
}
