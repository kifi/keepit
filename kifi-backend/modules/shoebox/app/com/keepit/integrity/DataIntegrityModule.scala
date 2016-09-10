package com.keepit.integrity

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.google.inject.{ Provides, Singleton }

case class DataIntegrityModule() extends ScalaModule {
  def configure {
    //    bind[DataIntegrityPlugin].to[DataIntegrityPluginImpl].in[AppScoped]
    //    bind[UriIntegrityPlugin].to[UriIntegrityPluginImpl].in[AppScoped]
  }
}
