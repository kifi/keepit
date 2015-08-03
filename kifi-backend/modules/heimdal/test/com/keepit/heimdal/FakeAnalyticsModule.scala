package com.keepit.heimdal

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.net.{ WebService, FakeWebService }
import com.keepit.model.{ Organization, User }

import scala.concurrent.Future

case class FakeAnalyticsModule() extends AnalyticsModule {

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
  def orgProvider(): PrimaryOrgProvider = new PrimaryOrgProvider() {
    override def getPrimaryOrg(userId: Id[User]): Future[Option[Id[Organization]]] = Future.successful(None)
  }
}

