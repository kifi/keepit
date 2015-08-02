package com.keepit.heimdal

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model._

import com.keepit.common.healthcheck.AirbrakeNotifier

import net.codingwell.scalaguice.ScalaModule

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Provides, Singleton }

import play.api.Play.current
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.JsObject

import scala.concurrent.Future

trait AnalyticsModule extends ScalaModule

case class ProdAnalyticsModule() extends AnalyticsModule {

  def configure() = {}

  @Provides @Singleton
  def mixpanel(primaryOrgProvider: PrimaryOrgProvider): MixpanelClient = {
    val projectToken: String = current.configuration.getString("mixpanel.token").get
    new MixpanelClientImpl(projectToken, primaryOrgProvider)
  }

  @Provides @Singleton
  def amplitude(primaryOrgProvider: PrimaryOrgProvider, amplitudeTransport: AmplitudeTransport): AmplitudeClient = {
    new AmplitudeClientImpl(primaryOrgProvider, amplitudeTransport)
  }

  @Provides @Singleton
  def orgProvider(primaryOrgForUserCache: PrimaryOrgForUserCache, shoeboxServiceClient: ShoeboxServiceClient): PrimaryOrgProvider = {
    new PrimaryOrgProviderImpl(primaryOrgForUserCache, shoeboxServiceClient)
  }
}

case class DevAnalyticsModule() extends AnalyticsModule {

  def configure() = {}

  @Provides @Singleton
  def mixpanel(): MixpanelClient = {
    new MixpanelClient {
      def alias(userId: Id[User], externalId: ExternalId[User]): Future[Unit] = Future.successful(())
      def delete(userId: Id[User]): Future[Unit] = Future.successful(())
      def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Future[Unit] = Future.successful(())
      def setUserProperties(userId: Id[User], properties: HeimdalContext): Future[Unit] = Future.successful(())
      def track[E <: HeimdalEvent](event: E)(implicit companion: HeimdalEventCompanion[E]): Future[Unit] = Future.successful(())
    }
  }

  @Provides @Singleton
  def amplitude(primaryOrgProvider: PrimaryOrgProvider, amplitudeTransport: AmplitudeTransport): AmplitudeClient = {
    new AmplitudeClientImpl(primaryOrgProvider, amplitudeTransport)
  }

  @Provides @Singleton
  def orgProvider(): PrimaryOrgProvider = new PrimaryOrgProvider() {
    override def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]] = Future.successful(None)
  }
}

trait AmplitudeTransportModule extends ScalaModule

case class ProdAmplitudeTransportModule() extends AmplitudeTransportModule {
  def configure() = {}

  @Provides @Singleton
  def transport(): AmplitudeTransport = {
    val apiKey: String = current.configuration.getString("amplitude.api_key").get
    new AmplitudeTransportImpl(apiKey)
  }
}

case class DevAmplitudeTransportModule() extends AmplitudeTransportModule {
  def configure() = {}

  @Provides @Singleton
  def transport(): AmplitudeTransport = new AmplitudeTransport {
    override def deliver(eventData: JsObject): Future[JsObject] = Future(eventData)
  }
}

