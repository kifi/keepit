package com.keepit.abook

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{CommonServiceModule, ConfigurationModule}
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.common.store.StoreModule

abstract class ABookModule(
  // Common Functional Modules
  val cacheModule: CacheModule,
  val storeModule: StoreModule,
  val contactsUpdaterPluginModule: ContactsUpdaterPluginModule
) extends ConfigurationModule with CommonServiceModule  {
  // Service clients
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val abookSlickModule = ABookSlickModule()
}
