package com.keepit.curator.model

import com.kifi.macros.json

@json case class RecommendationSource(value: String)

object RecommendationSource {
  object Site extends RecommendationSource("site")
  object IOS extends RecommendationSource("ios")
  object Android extends RecommendationSource("android")
  object Extension extends RecommendationSource("extension")
  object Email extends RecommendationSource("email")
  object Unknown extends RecommendationSource("unknown")
}

@json case class RecommendationSubSource(value: String)

object RecommendationSubSource {
  object RecommendationsFeed extends RecommendationSubSource("recommendationsFeed")
  object Unknown extends RecommendationSubSource("unknown")
}
