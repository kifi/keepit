package com.keepit.common.store

import com.keepit.search._
import com.keepit.scraper._
import com.google.inject.{ Provides, Singleton }
import play.api.Play._
import java.io.File
import com.keepit.learning.porndetector.InMemoryPornWordLikelihoodStore
import com.keepit.learning.porndetector.PornWordLikelihoodStore
import com.keepit.scraper.embedly.EmbedlyStore
import com.keepit.scraper.embedly.InMemoryEmbedlyStoreImpl

case class ScraperTestStoreModule() extends StoreModule() {
  def configure() {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new FakeArticleStore()

  @Singleton
  @Provides
  def bayesPornDetectorStore(): PornWordLikelihoodStore = new FakePornWordLikelihoodStore()

  @Singleton
  @Provides
  def embedlyStore(): EmbedlyStore = new InMemoryEmbedlyStoreImpl()

  @Singleton
  @Provides
  def uriImageStore: S3URIImageStore = FakeS3URIImageStore()

}
