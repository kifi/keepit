package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationFeedback(
    delivered: Option[Boolean] = None,
    clicked: Option[Boolean] = None,
    kept: Option[Boolean] = None,
    trashed: Option[Boolean] = None,
    vote: Option[Boolean] = None,
    improvement: Option[String] = None) {
  override def toString = s"UriRecommendationFeedback(delivered:$delivered, clicked:$clicked, kept:$kept, trashed:$trashed, vote:$vote, improvement:$improvement)"
}
