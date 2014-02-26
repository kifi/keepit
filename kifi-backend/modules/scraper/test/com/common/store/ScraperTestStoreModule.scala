package com.keepit.common.store

import com.keepit.search._
import com.keepit.scraper._
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import java.io.File
import com.keepit.learning.porndetector.InMemoryPornWordLikelihoodStore
import com.keepit.learning.porndetector.PornWordLikelihoodStore
import com.keepit.common.store.FakePornWordLikelihoodStore

case class ScraperTestStoreModule() extends StoreModule() {
  def configure() {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new FakeArticleStore()

  @Singleton
  @Provides
  def screenshotStore(): S3ScreenshotStore = new FakeS3ScreenshotStore()

  @Singleton
  @Provides
  def bayesPornDetectorStore(): PornWordLikelihoodStore = new FakePornWordLikelihoodStore()

}
