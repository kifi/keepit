package com.keepit.dev

import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.ServiceType
import com.keepit.search.{SearchServices, SearchProdModule}
import com.keepit.shoebox.{ShoeboxServices, ShoeboxProdModule}
import play.api.Application
import play.api.Mode._
import com.keepit.scraper.ScraperServices
import com.keepit.abook.ABookServices
import com.keepit.cortex.CortexServices
import com.keepit.graph.GraphServices

object DevGlobal extends FortyTwoGlobal(Dev)
  with ShoeboxServices
  with SearchServices
  with ScraperServices
  with ABookServices
  with CortexServices
  with GraphServices {
  override val module = Modules.`override`(GraphDevModule()).`with`(
    Modules.`override`(CortexDevModule()).`with`(
      Modules.`override`(ScraperDevModule()).`with`(
        Modules.`override`(ABookDevModule()).`with`(
          Modules.`override`(HeimdalDevModule()).`with`(
            Modules.`override`(ElizaDevModule()).`with`(
              Modules.`override`(SearchDevModule()).`with`(
                ShoeboxDevModule()
              )
            )
          )
        )
      )
    )
  )

  override def onStart(app: Application) {
    require(injector.instance[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")
    startShoeboxServices()
    startScraperServices()
    startSearchServices()
    startABookServices()
    startCortexServices()
    startGraphServices()
    super.onStart(app)
  }
}
