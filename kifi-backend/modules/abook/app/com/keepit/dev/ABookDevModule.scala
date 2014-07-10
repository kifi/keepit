package com.keepit.dev

import com.keepit.common.cache.ABookCacheModule
import com.keepit.abook.{ ProdABookServiceClientModule, DevContactsUpdaterPluginModule, ABookModule }
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ABookDevStoreModule
import com.keepit.common.queue.DevSimpleQueueModule

case class ABookDevModule() extends ABookModule(
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = ABookDevStoreModule(),
  contactsUpdaterPluginModule = DevContactsUpdaterPluginModule(),
  sqsModule = DevSimpleQueueModule()
) with CommonDevModule {

}
