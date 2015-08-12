package com.keepit.abook

import com.keepit.common.cache.ABookCacheModule
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.inject.CommonDevModule
import com.keepit.shoebox.FakeShoeboxServiceClientModule

case class ABookTestModule() extends ABookModule with CommonDevModule {
  val userActionsModule = FakeUserActionsModule()
  val cacheModule = ABookCacheModule(HashMapMemoryCacheModule())
  val storeModule = FakeABookStoreModule()
  val contactsUpdaterPluginModule = FakeABookImporterPluginModule()
  override val shoeboxServiceClientModule = FakeShoeboxServiceClientModule()
  override val abookServiceClientModule = FakeABookServiceClientModule()
}
