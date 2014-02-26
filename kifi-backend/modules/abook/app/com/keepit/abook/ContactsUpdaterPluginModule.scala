package com.keepit.abook

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provider, Provides}
import com.keepit.common.actor.ActorInstance
import akka.actor.ActorSystem

trait ContactsUpdaterPluginModule extends ScalaModule

case class ProdContactsUpdaterPluginModule() extends ContactsUpdaterPluginModule {
  def configure(): Unit = {
    install(ProdABookServiceClientModule())
  }

  @Provides
  def contactsUpdaterPlugin(actorInstance:ActorInstance[ContactsUpdaterActor], sysProvider:Provider[ActorSystem], updaterActorProvider:Provider[ContactsUpdaterActor]):ContactsUpdaterPlugin = {
    new ContactsUpdaterActorPlugin(actorInstance, sysProvider, updaterActorProvider, Runtime.getRuntime.availableProcessors)
  }
}

case class DevContactsUpdaterPluginModule() extends ContactsUpdaterPluginModule {
  def configure(): Unit = {
    install(ProdABookServiceClientModule())
  }

  @Provides
  def contactsUpdaterPlugin(actorInstance:ActorInstance[ContactsUpdaterActor], sysProvider:Provider[ActorSystem], updaterActorProvider:Provider[ContactsUpdaterActor]):ContactsUpdaterPlugin = {
    new ContactsUpdaterActorPlugin(actorInstance, sysProvider, updaterActorProvider, 1)
  }
}
