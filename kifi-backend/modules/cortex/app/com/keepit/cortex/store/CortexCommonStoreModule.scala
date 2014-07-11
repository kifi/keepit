package com.keepit.cortex.store

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.store.{ DevStoreModule, ProdStoreModule }

trait CortexCommonStoreModule extends ScalaModule

case class CortexCommonProdStoreModule() extends ProdStoreModule with CortexCommonStoreModule {
  def configure() {}
}

case class CortexCommonDevStoreModule() extends DevStoreModule(CortexCommonProdStoreModule()) with CortexCommonStoreModule {
  def configure() {}
}
