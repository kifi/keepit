package com.keepit.common.service

import com.google.inject.{Singleton, Inject, Provides}
import scala.collection.mutable.MutableList
import play.api.Mode
import play.api.Mode.Mode
import com.keepit.common.time._
import net.codingwell.scalaguice.ScalaModule

case class FakeServiceModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  def service(clock: Clock): FortyTwoServices = {
    new FortyTwoServices(clock, Mode.Test, None, None) {
      override lazy val currentVersion = ServiceVersion("0.0.0")
      override lazy val compilationTime = new SystemClock().now()
      override lazy val baseUrl = "test_kifi.com"
    }
  }
}
