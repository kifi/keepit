package com.keepit.model

import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource }
import com.kifi.macros.json

trait RecommendationFeedback

@json case class UriRecommendationFeedback(
    viewed: Option[Boolean] = None,
    clicked: Option[Boolean] = None,
    kept: Option[Boolean] = None,
    trashed: Option[Boolean] = None,
    vote: Option[Boolean] = None,
    comment: Option[String] = None,
    source: Option[RecommendationSource] = None,
    subSource: Option[RecommendationSubSource] = None) extends RecommendationFeedback {
  override def toString = s"UriRecommendationFeedback(viewed=$viewed clicked=$clicked kept=$kept trashed=$trashed vote=$vote comment=$comment source=$source subSource=$subSource)"
}

@json case class LibraryRecommendationFeedback(
    clicked: Option[Boolean] = None,
    followed: Option[Boolean] = None,
    trashed: Option[Boolean] = None,
    vote: Option[Boolean] = None,
    comment: Option[String] = None,
    source: Option[RecommendationSource] = None,
    subSource: Option[RecommendationSubSource] = None) extends RecommendationFeedback {
  override def toString = s"LibraryRecommendationFeedback(clicked=$clicked followed=$followed trashed=$trashed vote=$vote comment=$comment source=$source subSource=$subSource)"
}
