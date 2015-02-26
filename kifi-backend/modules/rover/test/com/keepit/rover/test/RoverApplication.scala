package com.keepit.rover.test

import com.google.inject.Module
import java.io.File

import com.google.inject.util.Modules
import com.keepit.common.cache.HashMapMemoryCacheModule
import com.keepit.inject.ApplicationInjector

import com.google.inject.Module
import java.io.File
import com.keepit.test.{ TestInjector, TestApplication }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.common.healthcheck.{ FakeHealthcheckModule, FakeMemoryUsageModule, FakeAirbrakeModule }
import com.keepit.common.time.FakeClockModule
import com.keepit.inject.{ EmptyInjector, ApplicationInjector, FakeFortyTwoModule }
import com.keepit.common.zookeeper.FakeDiscoveryModule
import com.keepit.common.cache.{ HashMapMemoryCacheModule }
import play.api.Mode
import com.google.inject.util.Modules
import com.keepit.shoebox.FakeShoeboxServiceClientModule
import com.keepit.rover.common.cache.RoverCacheModule
import com.keepit.rover.RoverServiceTypeModule

class RoverApplication(overridingModules: Module*)(implicit path: File = new File("./modules/rover/"))
  extends TestApplication(path, overridingModules, Seq(
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

trait RoverApplicationInjector extends ApplicationInjector

trait RoverTestInjector extends TestInjector {
  val module = Modules.combine(
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
