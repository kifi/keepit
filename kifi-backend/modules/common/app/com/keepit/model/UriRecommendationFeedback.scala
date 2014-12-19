package com.keepit.model

import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource }
import com.kifi.macros.json

@json case class UriRecommendationFeedback(
    clicked: Option[Boolean] = None,
    kept: Option[Boolean] = None,
    trashed: Option[Boolean] = None,
    vote: Option[Boolean] = None,
    comment: Option[String] = None,
    source: Option[RecommendationSource] = None,
    subSource: Option[RecommendationSubSource] = None) {
  override def toString = s"UriRecommendationFeedback(clicked=$clicked kept=$kept trashed=$trashed vote=$vote comment=$comment source=$source subSource=$subSource)"
}
