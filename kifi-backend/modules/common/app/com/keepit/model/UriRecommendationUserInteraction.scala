package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationUserInteraction(
    emailVote: Option[Boolean] = None) {
  override def toString = s"UriRecommendationUserInteraction(vote:$emailVote)"
}
