package com.keepit.curator.commanders

import com.keepit.curator.model.{ SeedItem, ScoredSeedItem }

import com.google.inject.{ Inject, Singleton }

@Singleton
class UriScoringHelper @Inject() () {

  def apply(items: Seq[SeedItem]): Seq[ScoredSeedItem] = ???

  def apply(item: SeedItem): ScoredSeedItem = ???

}
