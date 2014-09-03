package com.keepit.model

import com.keepit.curator.model.RecommendationClientType
import com.kifi.macros.json

@json case class UriRecommendationFeedback(
    clicked: Option[Boolean] = None,
    kept: Option[Boolean] = None,
    trashed: Option[Boolean] = None,
    vote: Option[Boolean] = None,
    comment: Option[String] = None,
    clientType: Option[RecommendationClientType] = None) {
  override def toString = s"UriRecommendationFeedback(clicked:$clicked, kept:$kept, trashed:$trashed, vote:$vote, comment:$comment, clientType:$clientType)"
}
