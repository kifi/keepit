package com.keepit.inject

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import com.keepit.search.ProdSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.eliza.ProdElizaServiceClientModule
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.actor.{ActorSystemModule, ProdActorSystemModule, DevActorSystemModule}
import com.keepit.common.zookeeper.{DiscoveryModule, ProdDiscoveryModule, DevDiscoveryModule}
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.net.ProdHttpClientModule

abstract class AbstractModuleAccessor extends ScalaModule {
  protected def install0(module: ScalaModule) = install(module)
}

trait ConfigurationModule extends AbstractModuleAccessor with Logging { 

  final def configure() {
    log.info(s"Configuring ${this}")
    for (field <- getClass.getMethods if classOf[ScalaModule] isAssignableFrom field.getReturnType) {
      val startTime = System.currentTimeMillis
      val module = field.invoke(this).asInstanceOf[ScalaModule]
      install0(module)
      log.info(s"Installing ${module.getClass.getSimpleName}: took ${System.currentTimeMillis-startTime}ms")
    }
  }
}

trait CommonServiceModule {
  val fortyTwoModule: FortyTwoModule
  val actorSystemModule: ActorSystemModule
  val discoveryModule: DiscoveryModule

  // Service clients
  val searchServiceClientModule = ProdSearchServiceClientModule()
  val shoeboxServiceClientModule = ProdShoeboxServiceClientModule()
  val elizaServiceClientModule = ProdElizaServiceClientModule()

  val cryptoModule = ShoeboxCryptoModule()
  val healthCheckModule = ProdHealthCheckModule()
  val httpClientModule = ProdHttpClientModule()
}

trait CommonProdModule extends CommonServiceModule {
  val fortyTwoModule = ProdFortyTwoModule()

  val actorSystemModule = ProdActorSystemModule()
  val discoveryModule = ProdDiscoveryModule()
}

trait CommonDevModule extends CommonServiceModule {
  val fortyTwoModule = ProdFortyTwoModule()

  val actorSystemModule = DevActorSystemModule()
  val discoveryModule = DevDiscoveryModule()
}
