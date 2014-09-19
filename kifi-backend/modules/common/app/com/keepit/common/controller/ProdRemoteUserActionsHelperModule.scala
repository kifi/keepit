package com.keepit.common.controller

class ProdRemoteUserActionsHelperModule extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[RemoteUserActionsHelper]
  }
}
