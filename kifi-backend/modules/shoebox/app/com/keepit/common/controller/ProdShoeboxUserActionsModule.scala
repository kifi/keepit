package com.keepit.common.controller

case class ProdShoeboxUserActionsModule() extends UserActionsModule {
  def configure(): Unit = {
    bind[UserActionsHelper].to[ShoeboxUserActionsHelper]
  }
}
