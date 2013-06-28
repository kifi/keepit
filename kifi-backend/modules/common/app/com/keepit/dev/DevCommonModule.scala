package com.keepit.dev

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import com.keepit.inject.AppScoped
import com.google.inject.{Inject, Provides, Singleton}
import com.keepit.common.analytics._
import play.api.Play._
import com.mongodb.casbah.MongoConnection
import com.keepit.common.actor.ActorPlugin
import akka.actor.ActorSystem
import play.api.Play
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.analytics.Event
import com.keepit.module.LocalDiscoveryModule

class DevCommonModule extends ScalaModule with Logging {
  def configure() {
    install(new S3DevModule)
  }

  @Singleton
  @Provides
  def mongoEventStore(): MongoEventStore = {
    current.configuration.getString("mongo.events.server").map { server =>
      val mongoConn = MongoConnection(server)
      val mongoDB = mongoConn(current.configuration.getString("mongo.events.database").getOrElse("events"))
      new MongoS3EventStoreImpl(mongoDB)
    }.getOrElse {
      new FakeMongoS3EventStoreImpl()
    }
  }
}
