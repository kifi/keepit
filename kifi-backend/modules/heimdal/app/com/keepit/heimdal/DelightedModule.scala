package com.keepit.heimdal

import com.google.inject.{ Provides, Singleton }
import com.keepit.commander.{ DevDelightedCommander, DelightedCommander, DelightedConfig }
import net.codingwell.scalaguice.ScalaModule
import play.api.Play._

trait DelightedModule extends ScalaModule

case class ProdDelightedModule() extends DelightedModule {

  def configure() = {}

  @Singleton
  @Provides
  def delightedConfig: DelightedConfig = DelightedConfig(
    current.configuration.getString("delighted.url").get,
    current.configuration.getString("delighted.key").get
  )
}

case class DevDelightedModule() extends DelightedModule {

  def configure() = {
    bind[DelightedCommander].to[DevDelightedCommander]
  }
}
