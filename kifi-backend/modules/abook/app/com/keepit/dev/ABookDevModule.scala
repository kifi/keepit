package com.keepit.dev

import com.keepit.common.cache.ABookCacheModule
import com.keepit.abook.{ DevABookImporterPluginModule, ABookModule }
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.controller.{ DevRemoteUserActionsHelperModule }
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ABookDevStoreModule

case class ABookDevModule() extends ABookModule with CommonDevModule {
  val userActionsModule = DevRemoteUserActionsHelperModule()
  val cacheModule = ABookCacheModule(HashMapMemoryCacheModule())
  val storeModule = ABookDevStoreModule()
  val contactsUpdaterPluginModule = DevABookImporterPluginModule()
}
