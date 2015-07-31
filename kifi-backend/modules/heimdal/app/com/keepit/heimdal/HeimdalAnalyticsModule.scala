package com.keepit.heimdal

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model._

import com.keepit.common.healthcheck.AirbrakeNotifier

import net.codingwell.scalaguice.ScalaModule

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Provides, Singleton }

import play.api.Play.current
import com.keepit.shoebox.ShoeboxServiceClient

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
  def amplitude(primaryOrgProvider: PrimaryOrgProvider): AmplitudeClient = {
    val apiKey: String = current.configuration.getString("amplitude.api_key").get
    new AmplitudeClientImpl(apiKey, primaryOrgProvider)
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
  def amplitude(primaryOrgProvider: PrimaryOrgProvider): AmplitudeClient = {
    val apiKey: String = current.configuration.getString("amplitude.api_key").get
    new AmplitudeClientImpl(apiKey, primaryOrgProvider)
  }
}
