package com.keepit.dev

import com.keepit.common.cache.ABookCacheModule
import com.keepit.abook.{DevContactsUpdaterPluginModule, ABookModule}
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ABookDevStoreModule

case class ABookDevModule() extends ABookModule (
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = ABookDevStoreModule(),
  contactsUpdaterPluginModule = DevContactsUpdaterPluginModule()
) with CommonDevModule {

}
