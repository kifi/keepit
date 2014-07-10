package com.keepit.common.store

import com.google.inject.{ Singleton, Provides }
import com.keepit.search.tracker._
import com.keepit.search.index.{ InMemoryIndexStoreImpl, IndexStore }

case class SearchFakeStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def clickHistoryStore(): ClickHistoryStore = {
    new InMemoryClickHistoryStoreImpl()
  }

  @Provides @Singleton
  def indexStore(): IndexStore = {
    new InMemoryIndexStoreImpl()
  }
}
