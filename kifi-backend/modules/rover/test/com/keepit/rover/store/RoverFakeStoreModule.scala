package com.keepit.rover.store

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.store.FakeStoreModule

case class RoverFakeStoreModule() extends FakeStoreModule with RoverStoreModule {

  @Provides @Singleton
  def roverArticleStore(): RoverUnderlyingArticleStore = new InMemoryRoverUnderlyingArticleStoreImpl()

  @Provides @Singleton
  def roverImageStore(): RoverImageStore = new InMemoryRoverImageStoreImpl()
}
