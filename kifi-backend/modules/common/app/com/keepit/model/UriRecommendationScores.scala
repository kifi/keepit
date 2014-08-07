package com.keepit.model
import com.kifi.macros.json

@json case class UriRecommendationScores(
    socialScore: Option[Float] = None,
    popularityScore: Option[Float] = None,
    overallInterestScore: Option[Float] = None,
    recentInterestScore: Option[Float] = None,
    recencyScore: Option[Float] = None,
    priorScore: Option[Float] = None,
    rekeepScore: Option[Float] = None,
    discoveryScore: Option[Float] = None) {
  override def toString = s"UriRecommendationScores(socialScore:$socialScore, popularityScore:$popularityScore, overallInterestScore:$overallInterestScore, overallInterestScore:$overallInterestScore, recentInterestScore:$recentInterestScore, recencyScore:$recencyScore, priorScore:$priorScore, rekeepScore:$rekeepScore, discoveryScore:$discoveryScore)"
}
