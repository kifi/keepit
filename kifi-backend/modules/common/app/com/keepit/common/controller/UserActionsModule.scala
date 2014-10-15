package com.keepit.common.controller

import net.codingwell.scalaguice.ScalaModule

trait UserActionsModule extends ScalaModule

case class DevRemoteUserActionsHelperModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[RemoteUserActionsHelper]
  }
}
