package com.keepit.common.store

import com.google.inject.{ Provides, Singleton }
import com.keepit.scraper.store.UriImageStoreInbox
import org.apache.commons.io.FileUtils
import play.api.Play._

case class ScraperProdStoreModule() extends ProdStoreModule {
  def configure() {
  }

  @Provides @Singleton
  def uriImageStoreInbox: UriImageStoreInbox = {
    val inboxDir = forceMakeTemporaryDirectory(current.configuration.getString("scraper.temporary.directory").get, "uri_images")
    UriImageStoreInbox(inboxDir)
  }
}

case class ScraperDevStoreModule() extends DevStoreModule(ScraperProdStoreModule()) {
  def configure() {
  }

  @Provides @Singleton
  def uriImageStoreInbox: UriImageStoreInbox = whenConfigured("scraper.temporary.directory")(prodStoreModule.uriImageStoreInbox) getOrElse {
    UriImageStoreInbox(FileUtils.getTempDirectory)
  }
}
