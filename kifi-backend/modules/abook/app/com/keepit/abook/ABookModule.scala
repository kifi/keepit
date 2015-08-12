package com.keepit.abook

import com.keepit.common.cache.CacheModule
import com.keepit.common.controller.UserActionsModule
import com.keepit.social.RemoteSecureSocialModule
import com.keepit.inject.{ CommonServiceModule, ConfigurationModule }
import com.keepit.shoebox.{ ShoeboxServiceClientModule, ProdShoeboxServiceClientModule }
import com.keepit.common.store.StoreModule
import com.keepit.graph.{ ProdGraphServiceClientModule, GraphServiceClientModule }
import com.keepit.common.zookeeper.ServiceTypeModule
import com.keepit.common.service.ServiceType

case class ABookServiceTypeModule() extends ServiceTypeModule {
  val serviceType = ServiceType.ABOOK
  val servicesToListenOn = ServiceType.SHOEBOX :: ServiceType.GRAPH :: Nil
}

trait ABookModule extends ConfigurationModule with CommonServiceModule {

  // Common Functional Modules
  val userActionsModule: UserActionsModule
  val cacheModule: CacheModule
  val storeModule: StoreModule
  val contactsUpdaterPluginModule: ABookImporterPluginModule
  val emailAccountUpdaterPluginModule: EmailAccountUpdaterPluginModule = EmailAccountUpdaterPluginModule()

  // Service clients
  val serviceTypeModule = ABookServiceTypeModule()
  val graphServiceClientModule: GraphServiceClientModule = ProdGraphServiceClientModule()
  val shoeboxServiceClientModule: ShoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val abookServiceClientModule: ABookServiceClientModule = ProdABookServiceClientModule()
  val secureSocialModule = RemoteSecureSocialModule()
  val abookSlickModule = ABookSlickModule()

  val dbSequencingModule = ABookDbSequencingModule()
}
