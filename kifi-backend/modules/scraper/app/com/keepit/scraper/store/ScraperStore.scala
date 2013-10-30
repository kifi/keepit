package com.keepit.common.store

case class ScraperProdStoreModule() extends ProdStoreModule {
  def configure() {
  }
}

case class ScraperDevStoreModule() extends DevStoreModule(ScraperProdStoreModule()) {
  def configure() {
  }
}
