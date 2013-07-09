package com.keepit.test

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.healthcheck._
import com.keepit.common.net.FakeClientResponse
import com.keepit.common.time._
import com.keepit.common.zookeeper._
import com.keepit.inject._
import com.keepit.search._
import com.keepit.shoebox._
import akka.actor.ActorSystem
import java.io.File
import com.keepit.FortyTwoGlobal
import com.keepit.common.store.{DevStoreModule, ProdStoreModule}
import com.keepit.search.SearchConfigModule
import com.keepit.common.net.FakeHttpClientModule

class DeprecatedTestApplication(_global: FortyTwoGlobal, useDb: Boolean = true, override val path: File = new File(".")) extends play.api.test.FakeApplication(path = path) {

  private def createTestGlobal(baseGlobal: FortyTwoGlobal, modules: Module*) = if (useDb)
    new DeprecatedTestGlobal(Modules.`override`(baseGlobal.module).`with`(modules: _*))
  else
    new DeprecatedTestRemoteGlobal(Modules.`override`(baseGlobal.module).`with`(modules: _*))

  override lazy val global = createTestGlobal(_global, FakeClockModule(), TestActorSystemModule()) // Play 2.1 makes global a lazy val, which can't be directly overridden.

  lazy val devStoreModule = new DevStoreModule(new ProdStoreModule { def configure {} }) { def configure {} }

  def withFakeHttpClient(requestToResponse: PartialFunction[String, FakeClientResponse] = FakeClientResponse.emptyFakeHttpClient) = overrideWith(FakeHttpClientModule(requestToResponse))
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withTestActorSystem(system: ActorSystem) = overrideWith(TestActorSystemModule(Some(system)))
  def withFakeCache() = overrideWith(TestCacheModule())
  def withS3DevModule() = overrideWith(devStoreModule)
  def withShoeboxServiceModule() = overrideWith(FakeShoeboxServiceModule())
  def withSearchConfigModule() = overrideWith(SearchConfigModule())

  def overrideWith(modules: Module*): DeprecatedTestApplication = new DeprecatedTestApplication(createTestGlobal(global, modules: _*), useDb, path)

}

class DeprecatedEmptyApplication(path: File = new File("./modules/common/")) extends DeprecatedTestApplication(new DeprecatedTestGlobal(DeprecatedTestModule()), path = path)

case class DeprecatedTestModule() extends ScalaModule {
  def configure(): Unit = {
    install(FakeHealthcheckModule())
    install(TestCacheModule())
    install(FakeDiscoveryModule())
    install(TestShoeboxServiceClientModule())
    install(TestSearchServiceClientModule())
    install(FakeHttpClientModule(FakeClientResponse.emptyFakeHttpClient))
    install(TestFortyTwoModule())
  }
}
