package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.search.{ SearchProdModule, SearchServices }
import com.google.inject.util.Modules
import play.api.Application

object SearchDevGlobal extends FortyTwoGlobal(Dev) with SearchServices {
  override val module = SearchDevModule()

  override def onStart(app: Application) {
    startSearchServices()
    super.onStart(app)
  }
}
