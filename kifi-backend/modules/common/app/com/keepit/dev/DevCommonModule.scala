package com.keepit.dev

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging
import com.keepit.common.cache._
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
import com.keepit.common.mail.MailToKeepPlugin
import com.keepit.common.analytics.Event

class DevCommonModule extends ScalaModule with Logging {
  def configure() {
    install(new S3DevModule)
    install(new EhCacheCacheModule)
    bind[FortyTwoCachePlugin].to[EhCacheCache]
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

  @Provides
  @AppScoped
  def actorPluginProvider: ActorPlugin =
    new ActorPlugin(ActorSystem("shoebox-dev-actor-system", Play.current.configuration.underlying, Play.current.classloader))
}

class FakeEventPersisterImpl @Inject() (
                                         system: ActorSystem, eventHelper: EventHelper, val schedulingProperties: SchedulingProperties) extends EventPersister with Logging {
  def persist(event: Event): Unit = {
    eventHelper.newEvent(event)
    log.info("Fake persisting event %s".format(event.externalId))
  }
  def persist(events: Seq[Event]): Unit = {
    log.info("Fake persisting events %s".format(events map (_.externalId) mkString(",")))
  }
}

class FakeMailToKeepPlugin @Inject() (val schedulingProperties: SchedulingProperties) extends MailToKeepPlugin with Logging {
  def fetchNewKeeps() {
    log.info("Fake fetching new keeps")
  }
}