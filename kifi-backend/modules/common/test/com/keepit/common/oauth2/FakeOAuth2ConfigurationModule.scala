package com.keepit.common.oauth2

import com.google.inject.{ Singleton, Provides }

case class FakeOAuth2ConfigurationModule() extends OAuth2ConfigurationModule {
  def configure(): Unit = {

  }

  @Provides
  @Singleton
  def getOAuth2Configuration(): OAuth2Configuration = {
    OAuth2Configuration(Map.empty)
  }

}
