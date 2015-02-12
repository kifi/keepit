package com.keepit.shoebox

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

case class ShoeboxTasksPluginModule() extends ScalaModule {
  def configure() = {
    bind[ShoeboxTasksPlugin].in[AppScoped]
  }
}
