package com.keepit.common.util

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.inject.AppScoped

case class PlayAppConfigurationModule() extends ScalaModule {
  def configure(): Unit = {
    bind[Configuration].to[PlayConfiguration].in[AppScoped]
  }

}

