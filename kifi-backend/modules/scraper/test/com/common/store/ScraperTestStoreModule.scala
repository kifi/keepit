package com.keepit.common.store

import com.keepit.rover.sensitivity.PornWordLikelihoodStore
import com.keepit.search._
import com.google.inject.{ Provides, Singleton }
import com.keepit.scraper.embedly.EmbedlyStore
import com.keepit.scraper.embedly.InMemoryEmbedlyStoreImpl

case class ScraperTestStoreModule() extends StoreModule() {
  def configure() {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new InMemoryArticleStoreImpl()

  @Singleton
  @Provides
  def bayesPornDetectorStore(): PornWordLikelihoodStore = new FakePornWordLikelihoodStore()

  @Singleton
  @Provides
  def embedlyStore(): EmbedlyStore = new InMemoryEmbedlyStoreImpl()

}
