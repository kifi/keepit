package com.keepit.eliza

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

case class ElizaTasksPluginModule() extends ScalaModule {
  def configure() = {
    bind[ElizaTasksPlugin].in[AppScoped]
  }
}
