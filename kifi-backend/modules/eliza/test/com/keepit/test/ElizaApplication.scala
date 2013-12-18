package com.keepit.test

import com.keepit.eliza.TestElizaServiceClientModule
import com.keepit.common.controller._
import net.codingwell.scalaguice.{ScalaMultibinder, ScalaModule}
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
import com.keepit.common.healthcheck.{FakeAirbrakeModule, FakeHealthcheckModule, FakeMemoryUsageModule}
import com.google.inject.util.Modules
import com.google.inject.Module
import com.keepit.common.cache.{HashMapMemoryCacheModule, ElizaCacheModule}
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.net.ProdHttpClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.abook.TestABookServiceClientModule

class TestGlobalWithDB(defaultModules: Seq[Module], overridingModules: Seq[Module])
  extends TestGlobal(defaultModules, overridingModules) {

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

class ElizaApplication(overridingModules: Module*)(implicit path: File = new File("./modules/Eliza/"))
  extends TestApplicationFromGlobal(path, new TestGlobalWithDB(
    Seq(
      TestABookServiceClientModule(),
      TestHeimdalServiceClientModule(),
      TestElizaServiceClientModule(),
      FakeAirbrakeModule(),
      FakeMemoryUsageModule(),
      FakeClockModule(),
      FakeHealthcheckModule(),
      TestFortyTwoModule(),
      FakeDiscoveryModule(),
      ElizaCacheModule(HashMapMemoryCacheModule())
    ), overridingModules
  ))

trait ElizaApplicationInjector extends ApplicationInjector with DbInjectionHelper with ElizaInjectionHelpers

trait ElizaTestInjector extends EmptyInjector with DbInjectionHelper with ElizaInjectionHelpers {
  val mode = Mode.Test
  val module = Modules.combine(
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    TestSlickModule(TestDbInfo.dbInfo),
    ElizaCacheModule(HashMapMemoryCacheModule())
  )
}
