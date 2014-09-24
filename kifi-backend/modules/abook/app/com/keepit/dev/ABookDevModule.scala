package com.keepit.dev

import com.keepit.common.cache.ABookCacheModule
import com.keepit.abook.{ DevABookImporterPluginModule, ABookModule }
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.controller.{ DevRemoteUserActionsHelperModule }
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ABookDevStoreModule
import com.keepit.common.queue.DevSimpleQueueModule

case class ABookDevModule() extends ABookModule(
  userActionsModule = DevRemoteUserActionsHelperModule(),
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = ABookDevStoreModule(),
  contactsUpdaterPluginModule = DevABookImporterPluginModule(),
  sqsModule = DevSimpleQueueModule()
) with CommonDevModule {

}
