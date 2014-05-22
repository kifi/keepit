package com.keepit.common.external

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.pagepeeker._

trait ExternalServiceModule extends ScalaModule

case class ProdExternalServiceModule() extends ExternalServiceModule {
  def configure() {
    bind[PagePeekerClient].to[PagePeekerClientImpl]
  }
}

case class DevExternalServiceModule() extends ExternalServiceModule {
  def configure() {
    bind[PagePeekerClient].to[DevPagePeekerClient]
  }
}
