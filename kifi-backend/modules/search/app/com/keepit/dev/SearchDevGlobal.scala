package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.search.{SearchProdModule, SearchServices}
import com.google.inject.util.Modules
import com.keepit.module.CommonModule
import play.api.Application

object SearchDevGlobal extends FortyTwoGlobal(Dev) with SearchServices {
  override val modules =
    Seq(Modules.`override`(new CommonModule, SearchProdModule()).`with`(SearchDevModule()))

  override def onStart(app: Application) {
    startSearchServices()
    super.onStart(app)
  }
}
