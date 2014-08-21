package com.keepit.curator.model

import com.kifi.macros.json

@json case class RecommendationClientType(value: String)

object RecommendationClientType {
  object Site extends RecommendationClientType("site")
  object IOS extends RecommendationClientType("ios")
  object Android extends RecommendationClientType("android")
  object Extension extends RecommendationClientType("extension")
  object Email extends RecommendationClientType("email")
  object Unknown extends RecommendationClientType("unknown")
}