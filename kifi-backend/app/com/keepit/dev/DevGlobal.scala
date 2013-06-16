package com.keepit.dev

import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.ServiceType
import com.keepit.module.CommonModule
import com.keepit.search.{SearchServices, SearchModule}
import com.keepit.shoebox.{ShoeboxServices, ShoeboxModule}
import play.api.Application
import play.api.Mode._
import com.keepit.search.SearchExclusiveModule

object DevGlobal extends FortyTwoGlobal(Dev) with ShoeboxServices with SearchServices {
  override val modules =
    Seq(Modules.`override`(
      Modules.`override`(new CommonModule, new SearchModule).`with`(new ShoeboxModule)
    ).`with`(new DevModule))

  override def onStart(app: Application) {
    require(injector.instance[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    startShoeboxServices()
    startSearchServices()
    super.onStart(app)
  }
}
