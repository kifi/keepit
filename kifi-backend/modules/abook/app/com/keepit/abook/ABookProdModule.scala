package com.keepit.abook

import com.keepit.common.cache.{ EhCacheCacheModule, MemcachedCacheModule, ABookCacheModule }
import com.keepit.common.controller.ProdRemoteUserActionsHelperModule
import com.keepit.inject.CommonProdModule
import com.keepit.common.store.ABookProdStoreModule

case class ABookProdModule() extends ABookModule with CommonProdModule {
  val userActionsModule = ProdRemoteUserActionsHelperModule()
  val cacheModule = ABookCacheModule(MemcachedCacheModule(), EhCacheCacheModule())
  val storeModule = ABookProdStoreModule()
  val contactsUpdaterPluginModule = ProdABookImporterPluginModule()
}
