package com.keepit.dev

import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.module.CommonModule
import com.keepit.search.{SearchServices, SearchModule}
import com.keepit.shoebox.{ShoeboxServices, ShoeboxModule}
import play.api.Application
import play.api.Mode._

object DevGlobal extends FortyTwoGlobal(Dev) with ShoeboxServices with SearchServices {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new ShoeboxModule, new SearchModule).`with`(new DevModule))

  override def onStart(app: Application) {
    require(injector.inject[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    startShoeboxServices()
    startSearchServices()
    super.onStart(app)
  }
}

object SearchDevGlobal extends FortyTwoGlobal(Dev) with SearchServices {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new SearchModule).`with`(new DevCommonModule, new SearchDevModule))

  override def onStart(app: Application) {
    startSearchServices()
    super.onStart(app)
  }
}

object ShoeboxDevGlobal extends FortyTwoGlobal(Dev) with ShoeboxServices {
  override val modules =
    Seq(Modules.`override`(new CommonModule, new ShoeboxModule).`with`(new DevCommonModule, new ShoeboxDevModule))

  override def onStart(app: Application) {
    startShoeboxServices()
    super.onStart(app)
  }
}
