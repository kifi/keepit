package com.keepit.common.service

import com.google.inject.{ Singleton, Inject, Provides }
import scala.collection.mutable.MutableList
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.{ TestFortyTwoModule, FortyTwoConfig }

case class FakeServiceModule() extends ScalaModule {

  def configure(): Unit = {
    install(TestFortyTwoModule())
  }

  @Provides
  def service(clock: Clock, fortytwoConfig: FortyTwoConfig): FortyTwoServices = {
    new FortyTwoServices(clock, Mode.Test, None, None, fortytwoConfig) {
      override lazy val currentVersion = ServiceVersion("00000000-0000-TEST-0000000")
      override lazy val compilationTime = new SystemClock().now()
      override lazy val baseUrl = "test_kifi.com"
    }
  }
}
