package com.keepit.abook

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provider, Provides }
import com.keepit.common.actor.ActorInstance
import akka.actor.ActorSystem

trait ABookImporterPluginModule extends ScalaModule {
  def configure(): Unit = {}
}

case class ProdABookImporterPluginModule() extends ABookImporterPluginModule {

  @Provides
  def contactsUpdaterPlugin(actorInstance: ActorInstance[ABookImporterActor], sysProvider: Provider[ActorSystem], updaterActorProvider: Provider[ABookImporterActor]): ABookImporterPlugin = {
    new ABookImporterActorPlugin(actorInstance, sysProvider, updaterActorProvider, Runtime.getRuntime.availableProcessors)
  }
}

case class DevABookImporterPluginModule() extends ABookImporterPluginModule {

  @Provides
  def contactsUpdaterPlugin(actorInstance: ActorInstance[ABookImporterActor], sysProvider: Provider[ActorSystem], updaterActorProvider: Provider[ABookImporterActor]): ABookImporterPlugin = {
    new ABookImporterActorPlugin(actorInstance, sysProvider, updaterActorProvider, 1)
  }
}
