package com.keepit.model

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.core._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.heimdal._

import scala.concurrent.Future

trait NonUserEventLoggingRepo extends EventRepo[NonUserEvent]

class ProdNonUserEventLoggingRepo(val mixpanel: MixpanelClient, val descriptors: NonUserEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
    extends MongoEventRepo[NonUserEvent] with NonUserEventLoggingRepo {
  val warnBufferSize = 2000
  val maxBufferSize = 10000

  private val augmentors = Seq(NonUserIdentifierAugmentor)

  override def persist(nonUserEvent: NonUserEvent): Future[Unit] =
    EventAugmentor.safelyAugmentContext(nonUserEvent, augmentors: _*).flatMap { augmentedContext =>
      super.persist(nonUserEvent.copy(context = augmentedContext))
    }
}

trait NonUserEventDescriptorRepo extends EventDescriptorRepo[NonUserEvent]

object NonUserIdentifierAugmentor extends EventAugmentor[NonUserEvent] {
  def isDefinedAt(nonUserEvent: NonUserEvent) = true
  def apply(nonUserEvent: NonUserEvent): Future[Seq[(String, ContextData)]] = Future.successful(Seq(
    "nonUserIdentifier" -> ContextStringData(nonUserEvent.identifier),
    "nonUserKind" -> ContextStringData(nonUserEvent.kind.name)
  ))
}

class NonUserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NonUserEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class NonUserEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "non_user_event_descriptor"
  def toKey(): String = name.name
}

class DevNonUserEventLoggingRepo extends DevEventRepo[NonUserEvent] with NonUserEventLoggingRepo
