package com.keepit.inject

import com.google.inject.{Singleton, Provides}
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import play.api.Mode._
import org.joda.time.DateTime
import com.keepit.common.service.ServiceVersion
import play.api.Play._
import com.keepit.common.service.ServiceVersion
import play.api.Mode
import play.api.Mode.Mode

case class TestFortyTwoModule() extends FortyTwoModule {

  @Provides @Singleton
  def fortyTwoServices(clock: Clock): FortyTwoServices = new FortyTwoServices(clock, Test, None, None) {
    override lazy val currentVersion: ServiceVersion = ServiceVersion("0.0.0")
    override lazy val compilationTime: DateTime = currentDateTime
  }

  @Provides @Singleton
  def playMode: Mode = Mode.Test

}
