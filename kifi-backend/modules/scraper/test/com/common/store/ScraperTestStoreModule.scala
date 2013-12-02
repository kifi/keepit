package com.keepit.common.store

import com.keepit.search._
import com.keepit.scraper._
import com.google.inject.{Provides, Singleton}
import play.api.Play._
import java.io.File

case class ScraperTestStoreModule() extends StoreModule() {
  def configure() {}

  @Singleton
  @Provides
  def articleStore(): ArticleStore = new FakeArticleStore()

  @Singleton
  @Provides
  def screenshotStore(): S3ScreenshotStore = new FakeS3ScreenshotStore()


}
