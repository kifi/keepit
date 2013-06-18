package com.keepit.dev

import com.keepit.FortyTwoGlobal
import play.api.Mode._
import com.keepit.search.{SearchExclusiveModule, SearchModule, SearchServices}
import com.google.inject.util.Modules
import com.keepit.module.CommonModule
import play.api.Application

object SearchDevGlobal extends FortyTwoGlobal(Dev) with SearchServices {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new SearchModule, new SearchExclusiveModule).`with`(new DevCommonModule, new SearchDevModule))

  override def onStart(app: Application) {
    startSearchServices()
    super.onStart(app)
  }
}
