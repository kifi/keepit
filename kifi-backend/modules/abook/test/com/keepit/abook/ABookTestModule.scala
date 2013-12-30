package com.keepit.abook

import com.keepit.abook.{TestContactsUpdaterPluginModule, FakeABookRawInfoStoreModule, ABookModule}
import com.keepit.common.cache.ABookCacheModule
import com.keepit.common.actor.DevActorSystemModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.CommonDevModule
import com.keepit.common.store.ABookDevStoreModule

case class ABookTestModule() extends ABookModule (
  cacheModule = ABookCacheModule(HashMapMemoryCacheModule()),
  storeModule = FakeABookRawInfoStoreModule(),
  contactsUpdaterPluginModule = TestContactsUpdaterPluginModule()
) with CommonDevModule {

}
