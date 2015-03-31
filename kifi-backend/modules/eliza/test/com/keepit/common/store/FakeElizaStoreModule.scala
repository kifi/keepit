package com.keepit.common.store

import net.codingwell.scalaguice.ScalaModule
import com.keepit.search.{ InMemoryArticleStoreImpl, ArticleStore }
import com.google.inject.{ Singleton, Provides }

case class FakeElizaStoreModule() extends ScalaModule {
  def configure() {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new InMemoryArticleStoreImpl()

}