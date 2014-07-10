package com.keepit.shoebox

import com.keepit.inject.AppScoped

import net.codingwell.scalaguice.ScalaModule
import com.keepit.commanders.{ RawKeepImporterPluginImpl, RawKeepImporterPlugin }

case class KeepImportsModule() extends ScalaModule {

  def configure {
    bind[RawKeepImporterPlugin].to[RawKeepImporterPluginImpl].in[AppScoped]
  }

}
