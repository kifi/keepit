package com.keepit.model

import com.kifi.macros.json

@json case class MarkedAsBad(
    bad: Boolean,
    reason: Option[String]) {
  override def toString = s"MarkedAsBad(bad:$bad, reason:$reason)"
}

@json case class UriRecommendationFeedback(
    delivered: Option[Boolean] = None,
    clicked: Option[Boolean] = None,
    kept: Option[Boolean] = None,
    trashed: Option[Boolean] = None,
    markedBad: Option[MarkedAsBad] = None) {
  override def toString = s"UriRecommendationFeedback(delivered:$delivered, clicked:$clicked, kept:$kept, trashed:$trashed, markedBad:$markedBad)"
}
