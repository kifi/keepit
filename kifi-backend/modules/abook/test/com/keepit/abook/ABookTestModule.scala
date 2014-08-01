package com.keepit.abook

import com.keepit.common.cache.ABookCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.shoebox.{ TestShoeboxServiceClientModule, ProdShoeboxServiceClientModule }

case class ABookTestModule() extends ABookModule(
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = FakeABookStoreModule(),
  contactsUpdaterPluginModule = TestABookImporterPluginModule(),
  sqsModule = FakeSimpleQueueModule()
) with CommonDevModule {
  override val shoeboxServiceClientModule = TestShoeboxServiceClientModule()
  override val abookServiceClientModule = FakeABookServiceClientModule()
}
