package com.keepit.common.net

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}

case class FakeHttpClientModule(requestToResponse: PartialFunction[String, FakeClientResponse]) extends ScalaModule {

  def configure(): Unit = {
    bind[HttpClient].toInstance(new FakeHttpClient(Some(requestToResponse)))
  }

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(Some("test.com"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(Some("test.com"))
}


