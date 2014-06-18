package com.keepit.shoebox

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule

case class ShoeboxDbSequencingModule() extends ScalaModule {

  def configure {
    bind[NormalizedURISequencingPlugin].to[NormalizedURISequencingPluginImpl].in[AppScoped]
  }

}
