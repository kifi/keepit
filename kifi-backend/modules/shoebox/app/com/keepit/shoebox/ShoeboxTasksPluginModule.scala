package com.keepit.shoebox

import com.keepit.commanders.{ SuggestedSearchTermUpdatePlugin, SuggestedSearchTermUpdatePluginImpl }
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

case class ShoeboxTasksPluginModule() extends ScalaModule {
  def configure() = {
    bind[ShoeboxTasksPlugin].in[AppScoped]
    bind[SuggestedSearchTermUpdatePlugin].to[SuggestedSearchTermUpdatePluginImpl].in[AppScoped]
  }
}
