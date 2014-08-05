package com.keepit.search

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.akka.MonitoredAwait
import java.io.File

case class FakeSearchConfigModule() extends ScalaModule {

  def configure() {}

  @Singleton
  @Provides
  def searchConfigManager(shoeboxClient: ShoeboxServiceClient, monitoredAwait: MonitoredAwait): SearchConfigManager = {
    new SearchConfigManager(None, shoeboxClient, monitoredAwait)
  }
}

