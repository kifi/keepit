package com.keepit.heimdal

import reactivemongo.bson.BSONDocument
import reactivemongo.api.collections.default.BSONCollection

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait SystemEventLoggingRepo extends EventRepo[SystemEvent]

class ProdSystemEventLoggingRepo(val collection: BSONCollection, val mixpanel: MixpanelClient, val descriptors: SystemEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
  extends MongoEventRepo[SystemEvent] with SystemEventLoggingRepo {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: SystemEvent) : BSONDocument = BSONDocument(EventRepo.eventToBSONFields(event))
  def fromBSON(bson: BSONDocument): SystemEvent = ???
}

trait SystemEventDescriptorRepo extends EventDescriptorRepo[SystemEvent]

class ProdSystemEventDescriptorRepo(val collection: BSONCollection, cache: SystemEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[SystemEvent] with SystemEventDescriptorRepo {
  override def upsert(obj: EventDescriptor) = super.upsert(obj) map { _ tap { _ => cache.set(SystemEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(SystemEventDescriptorNameKey(name)) { super.getByName(name) }
}

class SystemEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SystemEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class SystemEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "system_event_descriptor"
  def toKey(): String = name.name
}

class DevSystemEventLoggingRepo extends DevEventRepo[SystemEvent] with SystemEventLoggingRepo
