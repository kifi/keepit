package com.keepit.shoebox

import com.keepit.inject.AppScoped

import net.codingwell.scalaguice.ScalaModule
import com.keepit.commanders.{ RawKeepImporterPluginImpl, RawKeepImporterPlugin }
import com.keepit.common.plugin.SchedulerPlugin
import com.google.inject.Inject

case class FakeKeepImportsModule() extends ScalaModule {

  def configure {
    bind[RawKeepImporterPlugin].to[FakeRawKeepImporterPluginImpl]
  }

}

class FakeRawKeepImporterPluginImpl() extends RawKeepImporterPlugin {

  def processKeeps(broadcastToOthers: Boolean = false): Unit = {}

  override def onStart() {
    super.onStart()
  }
}
