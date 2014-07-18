package com.keepit.curator

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.curator.commanders.SeedIngestionPlugin

case class SeedIngestionPluginModule() extends ScalaModule {
  def configure() = {
    bind[SeedIngestionPlugin].in[AppScoped]
  }
}
