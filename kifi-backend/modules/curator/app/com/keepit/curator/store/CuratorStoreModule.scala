package com.keepit.curator.store

import com.keepit.common.logging.Logging
import com.keepit.common.store.{ DevStoreModule, ProdStoreModule, StoreModule }

trait CuratorStoreModule extends StoreModule with Logging

case class CuratorProdStoreModule() extends ProdStoreModule with CuratorStoreModule {
  def configure() {}
}

case class CuratorDevStoreModule() extends DevStoreModule(CuratorProdStoreModule()) with CuratorStoreModule {
  def configure() {}
}
