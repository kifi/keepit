package com.keepit.search

import com.keepit.common.cache.CacheModule
import com.keepit.social.SecureSocialModule
import com.keepit.shoebox.ShoeboxServiceClientModule
import net.codingwell.scalaguice.ScalaModule
import com.keepit.model.{BrowsingHistoryModule, ClickHistoryModule}
import com.keepit.module.{ActorSystemModule, DiscoveryModule}

abstract class SearchModule(

  // Common Functional Modules
  val cacheModule: CacheModule,
  val secureSocialModule: SecureSocialModule,
  val shoeboxServiceClientModule: ShoeboxServiceClientModule,
  val clickHistoryModule: ClickHistoryModule,
  val browsingHistoryModule: BrowsingHistoryModule,
  val actorSystemModule: ActorSystemModule,
  val discoveryModule: DiscoveryModule,

  // Search Functional Modules
  val indexModule: IndexModule,
  val searchConfigModule: SearchConfigModule,
  val resultFeedbackModule: ResultFeedbackModule

) extends ScalaModule {
  final def configure() {
    println(s"Configuring ${this}")

    install(cacheModule)
    install(secureSocialModule)
    install(shoeboxServiceClientModule)
    install(clickHistoryModule)
    install(browsingHistoryModule)
    install(actorSystemModule)
    install(discoveryModule)

    install(indexModule)
    install(searchConfigModule)
    install(resultFeedbackModule)
  }
}
