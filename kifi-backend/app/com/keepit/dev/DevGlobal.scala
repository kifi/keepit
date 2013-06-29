package com.keepit.dev

import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.ServiceType
import com.keepit.module.CommonModule
import com.keepit.search.{SearchServices, SearchProdModule}
import com.keepit.shoebox.{ShoeboxServices, ShoeboxProdModule}
import play.api.Application
import play.api.Mode._

object DevGlobal extends FortyTwoGlobal(Dev) with ShoeboxServices with SearchServices {
  override val modules =
    Seq(
      Modules.`override`(
        Modules.`override`(new CommonModule, SearchProdModule()).`with`(SearchDevModule())
      ).`with`(ShoeboxDevModule())
    )

  override def onStart(app: Application) {
    require(injector.instance[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    startShoeboxServices()
    startSearchServices()
    super.onStart(app)
  }
}
