package com.keepit.rover.test

import com.keepit.common.concurrent.{ FakeExecutionContextModule, ExecutionContextModule }
import com.keepit.common.db.{ TestDbInfo, FakeSlickModule }
import com.google.inject.{ Injector, Module }
import java.io.File
import com.keepit.rover.model.ArticleInfoRepo
import com.keepit.test.{ TestInjectorProvider, DbInjectionHelper, TestInjector, TestApplication }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.healthcheck.{ FakeHealthcheckModule, FakeMemoryUsageModule, FakeAirbrakeModule }
import com.keepit.common.time.{ Clock, FakeClockModule }
import com.keepit.inject.{ ApplicationInjector, FakeFortyTwoModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule }
import com.google.inject.util.Modules
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.rover.RoverServiceTypeModule

class RoverApplication(overridingModules: Module*)(implicit path: File = new File("./modules/rover/"))
  extends TestApplication(path, overridingModules, Seq(
    FakeExecutionContextModule(),
    FakeSlickModule(TestDbInfo.dbInfo),
    RoverServiceTypeModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    FakeFortyTwoModule(),
    FakeDiscoveryModule(),
    RoverCacheModule(HashMapMemoryCacheModule())
  ))

trait RoverApplicationInjector extends TestInjectorProvider with ApplicationInjector with DbInjectionHelper with RoverInjectionHelpers

trait RoverTestInjector extends TestInjector with DbInjectionHelper with RoverInjectionHelpers {
  val module = Modules.combine(
    FakeSlickModule(TestDbInfo.dbInfo),
    FakeHttpClientModule(),
    RoverServiceTypeModule(),
    FakeHttpClientModule(),
    FakeAirbrakeModule(),
    FakeMemoryUsageModule(),
    FakeClockModule(),
    FakeHealthcheckModule(),
    RoverCacheModule(HashMapMemoryCacheModule())
  )
}

trait RoverInjectionHelpers { self: TestInjectorProvider =>

  def articleInfoRepo(implicit injector: Injector) = inject[ArticleInfoRepo]
  def clock(implicit injector: Injector) = inject[Clock]
}