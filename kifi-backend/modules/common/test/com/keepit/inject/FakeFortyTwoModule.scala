package com.keepit.inject

import com.google.inject.{ Singleton, Provides }
import com.keepit.common.time._
import com.keepit.common.service.FakeServiceModule
import play.api.Mode._
import org.joda.time.DateTime
import com.keepit.common.service.ServiceVersion

case class FakeFortyTwoModule() extends FortyTwoModule {
  override def configure(): Unit = {
    install(FakeServiceModule())
    super.configure()
    //check out http://blog.javabien.net/2014/05/02/find-out-if-guice-creates-circular-proxies/
    //binder().disableCircularProxies()
  }

  @Provides
  @Singleton
  def fortytwoConfig: FortyTwoConfig = FortyTwoConfig("TEST_MODE", "http://dev.ezkeep.com:9000")
}
