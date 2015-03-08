package com.keepit.rover.common.store

import com.keepit.common.logging.Logging
import com.keepit.common.store.{ DevStoreModule, ProdStoreModule, StoreModule }

trait RoverStoreModule extends StoreModule with Logging

case class RoverProdStoreModule() extends ProdStoreModule with RoverStoreModule {
  def configure {}
}

case class RoverDevStoreModule() extends DevStoreModule(RoverProdStoreModule()) with RoverStoreModule {
  def configure() {}
}
