package com.keepit.common.net

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.healthcheck._

case class FakeHttpClientModule(requestToResponse: PartialFunction[HttpUri, FakeClientResponse] = FakeClientResponse.emptyFakeHttpClient) extends ScalaModule {

  def configure(): Unit = {
    install(FakeAirbrakeModule())
    val fakeClient = new FakeHttpClient(Some(requestToResponse))
    bind[HttpClient].toInstance(fakeClient)
    bind[FakeHttpClient].toInstance(fakeClient)
  }

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(Some("test.com"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(Some("test.com"))
}
