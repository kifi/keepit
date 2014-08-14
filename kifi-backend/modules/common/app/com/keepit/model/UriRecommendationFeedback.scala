package com.keepit.model

import com.kifi.macros.json

@json case class UriRecommendationFeedback(
    delivered: Option[Int] = None,
    clicked: Option[Int] = None,
    kept: Option[Boolean] = None,
    deleted: Option[Boolean] = None,
    markedBad: Option[Boolean] = None) {
  override def toString = s"UriRecommendationFeedback(delivered:$delivered, clicked:$clicked, kept:$kept, deleted:$deleted, markedBad:$markedBad)"
}
