package com.keepit.dev

import com.google.inject.Module
import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import com.keepit.common.akka.{SlowRunningExecutionContext, SafeFuture}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.service.ServiceType
import com.keepit.search.{SearchServices, SearchProdModule}
import com.keepit.shoebox.{ShoeboxServices, ShoeboxProdModule}
import net.codingwell.scalaguice.ScalaModule
import play.api.Application
import play.api.Mode._
import com.keepit.scraper.ScraperServices
import com.keepit.abook.ABookServices
import com.keepit.cortex.CortexServices
import com.keepit.graph.GraphServices

import scala.concurrent.Future

object DevGlobal extends FortyTwoGlobal(Dev)
  with ShoeboxServices
  with SearchServices
  with ScraperServices
  with ABookServices
  with CortexServices
  with GraphServices {

  def composeModules(modules: Seq[Module]): Module = {
    modules match {
      case head :: tail if tail.nonEmpty =>
        Modules.`override`(head).`with`(composeModules(tail))
      case head :: Nil =>
        head
    }
  }

  // Modules are overridden on top of each other, so the last modules in this list have higher precedence
  val modules: Seq[Module] = Seq(GraphDevModule(), CortexDevModule(), ScraperDevModule(), ABookDevModule(), HeimdalDevModule(), ElizaDevModule(), SearchDevModule(), ShoeboxDevModule())

  override val module = composeModules(modules)

  override def onStart(app: Application) {
    require(injector.instance[FortyTwoServices].currentService == ServiceType.DEV_MODE,
        "DevGlobal can only be run on a dev service")

    def startServices() = {
      val t = System.currentTimeMillis
      startShoeboxServices()
      startScraperServices()
      startSearchServices()
      startABookServices()
      startCortexServices()
      startGraphServices()
      log.info(s"Started services in ${System.currentTimeMillis - t}ms")
    }

    app.configuration.getString("services.bootstrap") match {
      case Some("false") =>
      case _ =>
        startServices()
    }


    super.onStart(app)
  }
}
