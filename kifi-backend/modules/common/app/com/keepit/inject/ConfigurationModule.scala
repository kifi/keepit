package com.keepit.inject

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.concurrent.{ PlayDefaultExecutionContextModule, FakeExecutionContextModule, ExecutionContextModule }
import com.keepit.common.logging.Logging
import com.keepit.common.crypto.ShoeboxCryptoModule
import com.keepit.common.actor.{ ActorSystemModule, ProdActorSystemModule, DevActorSystemModule }
import com.keepit.common.oauth.{ DevOAuth1ConfigurationModule, ProdOAuth1ConfigurationModule, DevOAuth2ConfigurationModule, ProdOAuth2ConfigurationModule }
import com.keepit.common.zookeeper.{ ProdDiscoveryModule, ServiceTypeModule }
import com.keepit.common.util.PlayAppConfigurationModule
import com.keepit.common.zookeeper.{ DiscoveryModule, DevDiscoveryModule }
import com.keepit.common.healthcheck.ProdHealthCheckModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.common.healthcheck.{ ProdAirbrakeModule, DevAirbrakeModule, ProdMemoryUsageModule, DevMemoryUsageModule }
import com.keepit.common.aws.AwsModule

abstract class AbstractModuleAccessor extends ScalaModule {
  protected def install0(module: ScalaModule) = install(module)

}

trait ConfigurationModule extends AbstractModuleAccessor with Logging {

  final def configure() {
    log.debug(s"Configuring $this")
    preConfigure()
    val cache = scala.collection.mutable.HashSet[String]()

    for (field <- getClass.getMethods if classOf[ScalaModule] isAssignableFrom field.getReturnType) {
      val startTime = System.currentTimeMillis
      if (!cache.contains(field.getName)) {
        val module = field.invoke(this).asInstanceOf[ScalaModule]
        log.debug(s"Installing ${module.getClass.getSimpleName}: took ${System.currentTimeMillis - startTime}ms")
        install0(module)
        cache.add(field.getName)
      }
    }
  }

  def preConfigure(): Unit = {}
}

trait CommonServiceModule {
  val fortyTwoModule: FortyTwoModule
  val actorSystemModule: ActorSystemModule
  val serviceTypeModule: ServiceTypeModule
  val discoveryModule: DiscoveryModule

  val executionContextModule: ExecutionContextModule = PlayDefaultExecutionContextModule()
  val cryptoModule = ShoeboxCryptoModule()
  val healthCheckModule = ProdHealthCheckModule()
  val httpClientModule = ProdHttpClientModule()

  val awsModule = new AwsModule()
  val configModule = PlayAppConfigurationModule()
}

trait CommonProdModule extends CommonServiceModule {
  val fortyTwoModule = ProdFortyTwoModule()

  val actorSystemModule = ProdActorSystemModule()
  val discoveryModule = ProdDiscoveryModule()

  val airbrakeModule = ProdAirbrakeModule()
  val memoryUsageModule = ProdMemoryUsageModule()

  val oauth1ConfigModule = ProdOAuth1ConfigurationModule()
  val oauth2ConfigModule = ProdOAuth2ConfigurationModule()
}

trait CommonDevModule extends CommonServiceModule {
  val fortyTwoModule = ProdFortyTwoModule()

  val actorSystemModule = DevActorSystemModule()
  val discoveryModule = DevDiscoveryModule()

  val airbrakeModule = DevAirbrakeModule()
  val memoryUsageModule = DevMemoryUsageModule()

  val oauth1ConfigModule = DevOAuth1ConfigurationModule()
  val oauth2ConfigModule = DevOAuth2ConfigurationModule()
  override val executionContextModule = FakeExecutionContextModule()
}
