package com.keepit.common.net

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton, Provider}
import com.keepit.common.logging.AccessLog
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.Play._
import com.keepit.common.controller.MidFlightRequests
import com.keepit.common.amazon.MyAmazonInstanceInfo

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
  def httpClientProvider(airbrake: Provider[AirbrakeNotifier],
        accessLog: AccessLog, serviceDiscovery: ServiceDiscovery,
        fastJsonParser: FastJsonParser, midFlightRequests: MidFlightRequests,
        myInstanceInfo: MyAmazonInstanceInfo): HttpClient =
    new HttpClientImpl(airbrake = airbrake, accessLog = accessLog, serviceDiscovery = serviceDiscovery,
      fastJsonParser = fastJsonParser, midFlightRequests = midFlightRequests, myInstanceInfo = myInstanceInfo)
}
