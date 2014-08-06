package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationUserInteraction(
    good: Option[Boolean] = None,
    bad: Option[Boolean] = None) {
  override def toString = s"UriRecommendationUserInteraction(good:$good, bad:$bad)"
}
