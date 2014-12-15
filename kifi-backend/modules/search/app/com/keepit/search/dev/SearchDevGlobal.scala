package com.keepit.search.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.search.SearchServices
import play.api.Application

object SearchDevGlobal extends FortyTwoGlobal(Dev) with SearchServices {
  override val module = SearchDevModule()

  override def onStart(app: Application) {
    startSearchServices()
    super.onStart(app)
  }
}
