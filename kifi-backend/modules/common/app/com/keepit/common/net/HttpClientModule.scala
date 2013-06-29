package com.keepit.common.net

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import play.api.Play._
import com.keepit.common.healthcheck.HealthcheckPlugin

trait HttpClientModule extends ScalaModule

case class ProdHttpClientModule() extends HttpClientModule {
  def configure {}

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(current.configuration.getString("session.domain"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(current.configuration.getString("session.domain"))

  @Provides
  def httpClientProvider(healthcheckPlugin: HealthcheckPlugin): HttpClient = new HttpClientImpl(healthcheckPlugin = healthcheckPlugin)
}
