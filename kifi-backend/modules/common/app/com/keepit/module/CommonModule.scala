package com.keepit.module

import java.net.InetAddress

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.Provider
import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.common.controller.FortyTwoCookies._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.net.HttpClient
import com.keepit.common.net.HttpClientImpl
import com.keepit.inject.{FortyTwoModule, AppScoped}

import play.api.Mode.Mode
import play.api.Play.current

class CommonModule extends ScalaModule with Logging {

  def configure() {
    install(new FortyTwoModule)

    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in(classOf[AppScoped])
  }

  @Singleton
  @Provides
  def playMode: Mode = current.mode

  @Singleton
  @Provides
  def kifiInstallationCookie: KifiInstallationCookie = new KifiInstallationCookie(current.configuration.getString("session.domain"))

  @Singleton
  @Provides
  def impersonateCookie: ImpersonateCookie = new ImpersonateCookie(current.configuration.getString("session.domain"))

  @Provides
  def httpClientProvider(healthcheckPlugin: HealthcheckPlugin): HttpClient = new HttpClientImpl(healthcheckPlugin = healthcheckPlugin)

}
