package com.keepit.common.store

import com.keepit.common.logging.Logging

trait ElizaStoreModule extends StoreModule with Logging

case class ElizaProdStoreModule() extends ProdStoreModule with ElizaStoreModule {
  def configure() {}
}

case class ElizaDevStoreModule() extends DevStoreModule(ElizaProdStoreModule()) with ElizaStoreModule {
  def configure() {}
}
