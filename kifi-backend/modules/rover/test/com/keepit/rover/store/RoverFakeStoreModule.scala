package com.keepit.rover.store

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.store.{ InMemoryRoverImageStoreImpl, RoverImageStore, FakeStoreModule }

case class RoverFakeStoreModule() extends FakeStoreModule with RoverStoreModule {

  @Provides @Singleton
  def roverArticleStore(): RoverUnderlyingArticleStore = new InMemoryRoverUnderlyingArticleStoreImpl()

}
