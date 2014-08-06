package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationFeedback(
    val seen: Option[Boolean],
    val clicked: Option[Boolean],
    val kept: Option[Boolean]) {
  override def toString = s"UriRecommendationFeedback(seen:$seen, clicked:$clicked, kept:$kept)"
}
