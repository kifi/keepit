package com.keepit.test

import play.api.GlobalSettings
import play.api.Application
import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.common.healthcheck.FakeHealthcheck
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.shoebox.ShoeboxModule
import com.keepit.dev.DevModule
import com.keepit.inject._
import com.keepit.FortyTwoGlobal
import com.tzavellas.sse.guice.ScalaModule
import com.keepit.common.store.FakeStoreModule

class TestApplication(override val global: TestGlobal) extends play.api.test.FakeApplication() {
  def withFakeMail() = overrideWith(FakeMailModule())
  def withFakeHealthcheck() = overrideWith(FakeHealthcheckModule())
  def withFakeHttpClient() = overrideWith(FakeHttpClientModule())
  def withFakeStore() = overrideWith(FakeStoreModule())
  
  def overrideWith(model: Module): TestApplication = 
    new TestApplication(new TestGlobal(Modules.`override`(global.module).`with`(model))) 
}

class DevApplication() extends TestApplication(new TestGlobal(DevModule())) 
class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxModule())) 
class EmptyApplication() extends TestApplication(new TestGlobal(EmptyModule())) 

case class EmptyModule() extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
  }
}
