package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.scraper.{ ProdScraperServiceModule, ScraperServices }
import com.google.inject.util.Modules
import play.api.Application

object ScraperDevGlobal extends FortyTwoGlobal(Dev) with ScraperServices {
  override val module = ScraperDevModule()

  override def onStart(app: Application) {
    startScraperServices()
    super.onStart(app)
  }
}
