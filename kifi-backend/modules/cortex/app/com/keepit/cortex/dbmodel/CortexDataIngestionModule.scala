package com.keepit.cortex.dbmodel

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

trait CortexDataIngestionModule extends ScalaModule

case class CortexDataIngestionProdModule() extends CortexDataIngestionModule {
  def configure() {
    bind[CortexDataIngestionPlugin].to[CortexDataIngestionPluginImpl].in[AppScoped]
  }
}

case class CortexDataIngestionDevModule() extends CortexDataIngestionModule {
  def configure() {
    bind[CortexDataIngestionPlugin].to[CortexDataIngestionPluginImpl].in[AppScoped]
  }
}
