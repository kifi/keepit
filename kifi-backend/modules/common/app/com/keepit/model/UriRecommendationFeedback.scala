package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationFeedback(
    seen: Option[Boolean],
    clicked: Option[Boolean],
    kept: Option[Boolean]) {
  override def toString = s"UriRecommendationFeedback(seen:$seen, clicked:$clicked, kept:$kept)"
}
