package com.keepit.abook

import com.keepit.common.cache.ABookCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.queue.FakeSimpleQueueModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientModule, ProdShoeboxServiceClientModule }

case class ABookTestModule() extends ABookModule(
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = FakeABookStoreModule(),
  contactsUpdaterPluginModule = FakeABookImporterPluginModule(),
  sqsModule = FakeSimpleQueueModule()
) with CommonDevModule {
  override val shoeboxServiceClientModule = FakeShoeboxServiceClientModule()
  override val abookServiceClientModule = FakeABookServiceClientModule()
}
