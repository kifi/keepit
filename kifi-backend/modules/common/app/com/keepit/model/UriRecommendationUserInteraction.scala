package com.keepit.model

class UriRecommendationUserInteraction (
  good: Option[Boolean],
  bad: Option[Boolean] ) {
  override def toString = s"UriRecommendationUserInteraction(good:$good, bad:$bad)"
}
