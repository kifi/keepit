package com.keepit.common.store

import com.keepit.rover.sensitivity.PornWordLikelihoodStore
import com.keepit.search._
import com.google.inject.{ Provides, Singleton }

case class ScraperTestStoreModule() extends StoreModule() {
  def configure() {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new InMemoryArticleStoreImpl()

  @Singleton
  @Provides
  def bayesPornDetectorStore(): PornWordLikelihoodStore = new FakePornWordLikelihoodStore()

}
