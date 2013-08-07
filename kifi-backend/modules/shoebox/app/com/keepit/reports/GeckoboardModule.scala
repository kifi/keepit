package com.keepit.reports

import com.google.inject.{Provides, Singleton}
import com.keepit.shoebox.usersearch._
import com.keepit.common.db.slick._
import com.keepit.model._
import play.api.Play._
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

case class GeckoboardModule() extends ScalaModule {

  def configure {
    bind[GeckoboardReporterPlugin].to[GeckoboardReporterPluginImpl].in[AppScoped]
  }

}
