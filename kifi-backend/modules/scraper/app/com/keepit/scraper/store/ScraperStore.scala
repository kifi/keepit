package com.keepit.common.store

import com.google.inject.{ Provides, Singleton }
import org.apache.commons.io.FileUtils
import play.api.Play._

case class ScraperProdStoreModule() extends ProdStoreModule {
  def configure() {
  }

}

case class ScraperDevStoreModule() extends DevStoreModule(ScraperProdStoreModule()) {
  def configure() {
  }

}
