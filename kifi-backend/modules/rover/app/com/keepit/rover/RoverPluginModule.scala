package com.keepit.rover

import com.keepit.inject.AppScoped
import com.keepit.rover.manager.{ RoverManagerPluginImpl, RoverManagerPlugin }
import com.keepit.rover.model.{ ArticleInfoSequencingPluginImpl, ArticleInfoSequencingPlugin }
import net.codingwell.scalaguice.ScalaModule

case class RoverPluginModule() extends ScalaModule {
  def configure {
    bind[RoverManagerPlugin].to[RoverManagerPluginImpl].in[AppScoped]
    bind[ArticleInfoSequencingPlugin].to[ArticleInfoSequencingPluginImpl].in[AppScoped]
  }
}
