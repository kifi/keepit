package com.keepit.curator

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.curator.commanders.CuratorTasksPlugin

case class CuratorTasksPluginModule() extends ScalaModule {
  def configure() = {
    bind[CuratorTasksPlugin].in[AppScoped]
  }
}
