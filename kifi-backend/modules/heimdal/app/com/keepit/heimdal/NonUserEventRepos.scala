package com.keepit.heimdal

import reactivemongo.bson.BSONDocument
import reactivemongo.api.collections.default.BSONCollection

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait NonUserEventLoggingRepo extends EventRepo[NonUserEvent]

class ProdNonUserEventLoggingRepo(val collection: BSONCollection, val mixpanel: MixpanelClient, val descriptors: NonUserEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
  extends MongoEventRepo[NonUserEvent] with NonUserEventLoggingRepo {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: NonUserEvent) : BSONDocument = BSONDocument(EventRepo.eventToBSONFields(event))
  def fromBSON(bson: BSONDocument): NonUserEvent = ???
}

trait NonUserEventDescriptorRepo extends EventDescriptorRepo[NonUserEvent]

class ProdNonUserEventDescriptorRepo(val collection: BSONCollection, cache: NonUserEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[NonUserEvent] with NonUserEventDescriptorRepo {
  override def upsert(obj: EventDescriptor) = super.upsert(obj) map { _ tap { _ => cache.set(NonUserEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(NonUserEventDescriptorNameKey(name)) { super.getByName(name) }
}

class NonUserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[NonUserEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class NonUserEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "non_user_event_descriptor"
  def toKey(): String = name.name
}

class DevNonUserEventLoggingRepo extends DevEventRepo[NonUserEvent] with NonUserEventLoggingRepo
