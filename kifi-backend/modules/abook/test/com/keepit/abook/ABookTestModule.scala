package com.keepit.abook

import com.keepit.common.cache.ABookCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.queue.FakeSimpleQueueModule

case class ABookTestModule() extends ABookModule (
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = FakeABookStoreModule(),
  contactsUpdaterPluginModule = TestContactsUpdaterPluginModule(),
  sqsModule = FakeSimpleQueueModule()
) with CommonDevModule {

}
