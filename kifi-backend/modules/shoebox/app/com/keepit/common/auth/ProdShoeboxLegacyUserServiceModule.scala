package com.keepit.common.auth

case class ProdShoeboxLegacyUserServiceModule() extends LegacyUserServiceModule {
  def configure(): Unit = {
    bind[LegacyUserService].to[ShoeboxLegacyUserService]
  }

}
