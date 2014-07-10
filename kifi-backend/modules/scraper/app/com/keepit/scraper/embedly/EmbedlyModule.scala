package com.keepit.scraper.embedly

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

trait EmbedlyModule extends ScalaModule

case class ProdEmbedlyModule() extends EmbedlyModule {
  def configure() {
    bind[EmbedlyClient].to[EmbedlyClientImpl].in[AppScoped]
  }
}

case class DevEmbedlyModule() extends EmbedlyModule {
  def configure() {
    bind[EmbedlyClient].to[DevEmbedlyClient].in[AppScoped]
  }
}
