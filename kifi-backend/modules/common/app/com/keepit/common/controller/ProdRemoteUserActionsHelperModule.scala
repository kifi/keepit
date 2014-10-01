package com.keepit.common.controller

case class ProdRemoteUserActionsHelperModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[RemoteUserActionsHelper]
  }
}
