package com.keepit.common.seo

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

trait SiteMapGeneratorModule extends ScalaModule

case class ProdSiteMapGeneratorModule() extends SiteMapGeneratorModule {
  def configure() {
    bind[SiteMapGeneratorPlugin].to[SiteMapGeneratorPluginImpl].in[AppScoped]
  }
}

case class DevSiteMapGeneratorModule() extends SiteMapGeneratorModule {
  def configure() {
    bind[SiteMapGeneratorPlugin].to[SiteMapGeneratorPluginImpl].in[AppScoped]
  }
}
