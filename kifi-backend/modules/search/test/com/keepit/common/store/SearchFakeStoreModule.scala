package com.keepit.common.store

import com.google.inject.{Singleton, Provides}
import com.amazonaws.services.s3.AmazonS3
import com.keepit.common.logging.AccessLog
import com.keepit.search.tracker._
import com.keepit.search.tracker.ClickHistoryBuilder
import com.keepit.search.tracker.BrowsingHistoryBuilder
import com.keepit.search.index.{InMemoryIndexStoreImpl, IndexStore}

case class SearchFakeStoreModule() extends FakeStoreModule {

  @Provides @Singleton
  def browsingHistoryStore(): BrowsingHistoryStore = {
    new InMemoryBrowsingHistoryStoreImpl()
  }

  @Provides @Singleton
  def clickHistoryStore(): ClickHistoryStore = {
    new InMemoryClickHistoryStoreImpl()
  }

  @Provides @Singleton
  def indexStore(): IndexStore = {
    new InMemoryIndexStoreImpl()
  }
}
