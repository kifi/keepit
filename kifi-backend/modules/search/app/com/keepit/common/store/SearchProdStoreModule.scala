package com.keepit.common.store

case class SearchProdStoreModule() extends ProdStoreModule {
  def configure {}

}

case class SearchDevStoreModule() extends DevStoreModule(SearchProdStoreModule()) {
  def configure() {}
}