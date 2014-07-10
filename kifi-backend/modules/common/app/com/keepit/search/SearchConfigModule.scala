package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import play.api.Play._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import java.io.File

case class SearchConfigModule() extends ScalaModule {

  def configure() {}

  @Singleton
  @Provides
  def searchConfigManager(shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait): SearchConfigManager = {
    val optFile = current.configuration.getString("index.config").map(new File(_).getCanonicalFile).filter(_.exists)
    new SearchConfigManager(optFile, shoeboxClient, monitoredAwait)
  }
}

