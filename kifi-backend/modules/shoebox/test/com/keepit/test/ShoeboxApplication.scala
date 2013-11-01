package com.keepit.test

import play.api.{Application, Mode}
import com.keepit.inject.{TestFortyTwoModule, ApplicationInjector, EmptyInjector}
import com.keepit.common.db.{TestDbInfo}
import java.sql.{Driver, DriverManager}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RWSession
import scala.slick.session.ResultSetConcurrency
import java.io.File
import play.utils.Threads
import com.keepit.common.time.FakeClockModule
import com.keepit.common.db.TestSlickModule
import com.keepit.common.healthcheck.{FakeAirbrakeModule, FakeHealthcheckModule}
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{HashMapMemoryCacheModule, ShoeboxCacheModule}
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.scraper.{ProdScraperServiceClientModule, TestScraperServiceClientModule, FakeScraperModule}
import com.keepit.normalizer.TestNormalizationServiceModule
import com.keepit.eliza.TestElizaServiceClientModule
import com.keepit.common.net.ProdHttpClientModule

class TestGlobalWithDB(defaultModules: Seq[Module], overridingModules: Seq[Module])
  extends TestGlobal(defaultModules, overridingModules) {

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

class ShoeboxApplication(overridingModules: Module*)(implicit path: File = new File("./modules/shoebox/"))
  extends TestApplicationFromGlobal(path, new TestGlobalWithDB(
    Seq(
      TestElizaServiceClientModule(),
      FakeAirbrakeModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule(),
      TestSlickModule(TestDbInfo.dbInfo),
      ShoeboxCacheModule(HashMapMemoryCacheModule()),
      TestNormalizationServiceModule()
    ), overridingModules
  ))

trait ShoeboxApplicationInjector extends ApplicationInjector with DbInjectionHelper with ShoeboxInjectionHelpers

trait ShoeboxTestInjector extends EmptyInjector with DbInjectionHelper with ShoeboxInjectionHelpers {
  val mode = Mode.Test
  val module = Modules.combine(
    TestElizaServiceClientModule(),
    FakeAirbrakeModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    ShoeboxCacheModule(HashMapMemoryCacheModule()),
    TestNormalizationServiceModule(),
    TestScraperServiceClientModule()
  )
}
