package com.keepit.common

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.pagepeeker._
import com.keepit.common.embedly._

trait ExternalServiceModule extends ScalaModule

case class ProdExternalServiceModule() extends ExternalServiceModule {
  def configure() {
    bind[EmbedlyClient].to[EmbedlyClientImpl]
    bind[PagePeekerClient].to[PagePeekerClientImpl]
  }
}

case class DevExternalServiceModule() extends ExternalServiceModule {
  def configure() {
    bind[EmbedlyClient].to[DevEmbedlyClient]
    bind[PagePeekerClient].to[DevPagePeekerClient]
  }
}
