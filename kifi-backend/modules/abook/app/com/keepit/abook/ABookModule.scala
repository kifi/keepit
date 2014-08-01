package com.keepit.abook

import com.keepit.common.cache.CacheModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.{ ShoeboxServiceClientModule, ProdShoeboxServiceClientModule }
import com.keepit.common.store.StoreModule
import com.keepit.common.queue.SimpleQueueModule
import com.keepit.graph.{ ProdGraphServiceClientModule, GraphServiceClientModule }

abstract class ABookModule(
    // Common Functional Modules
    val cacheModule: CacheModule,
    val storeModule: StoreModule,
    val contactsUpdaterPluginModule: ABookImporterPluginModule,
    val emailAccountUpdaterPluginModule: EmailAccountUpdaterPluginModule = EmailAccountUpdaterPluginModule(),
    val sqsModule: SimpleQueueModule) extends ConfigurationModule with CommonServiceModule {
  // Service clients
  val graphServiceClientModule: GraphServiceClientModule = ProdGraphServiceClientModule()
  val shoeboxServiceClientModule: ShoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val abookServiceClientModule: ABookServiceClientModule = ProdABookServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val abookSlickModule = ABookSlickModule()

  val repoChangeListenerModule = AbookRepoChangeListenerModule()
  val dbSequencingModule = ABookDbSequencingModule()
}
