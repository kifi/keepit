package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationUserInteraction(
    vote: Option[Boolean] = None) {
  override def toString = s"UriRecommendationUserInteraction(vote:$vote)"
}
