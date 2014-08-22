package com.keepit.curator.commanders

import com.google.inject.Inject
import com.keepit.curator.model.UriRecommendationRepo

class CuratorCommander @Inject() (
  uriRecRepo: UriRecommendationRepo) {

  def reapOldRecommendations() {

  }
}
