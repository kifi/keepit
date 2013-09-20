package com.keepit.inject

import com.google.inject.{Singleton, Provides}
import com.keepit.common.time._
import com.keepit.common.service.FakeServiceModule
import play.api.Mode._
import org.joda.time.DateTime
import com.keepit.common.service.ServiceVersion

case class TestFortyTwoModule() extends FortyTwoModule {
  override def configure(): Unit = {
    install(FakeServiceModule())
    super.configure()
  }
}
