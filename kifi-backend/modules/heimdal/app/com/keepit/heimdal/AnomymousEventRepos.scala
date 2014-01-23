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

trait AnonymousEventLoggingRepo extends EventRepo[AnonymousEvent]

class ProdAnonymousEventLoggingRepo(val collection: BSONCollection, val mixpanel: MixpanelClient, val descriptors: AnonymousEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
  extends MongoEventRepo[AnonymousEvent] with AnonymousEventLoggingRepo {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: AnonymousEvent) : BSONDocument = BSONDocument(EventRepo.eventToBSONFields(event))
  def fromBSON(bson: BSONDocument): AnonymousEvent = ???
}

trait AnonymousEventDescriptorRepo extends EventDescriptorRepo[AnonymousEvent]

class ProdAnonymousEventDescriptorRepo(val collection: BSONCollection, cache: AnonymousEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[AnonymousEvent] with AnonymousEventDescriptorRepo {
  override def upsert(obj: EventDescriptor) = super.upsert(obj) map { _ tap { _ => cache.set(AnonymousEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(AnonymousEventDescriptorNameKey(name)) { super.getByName(name) }
}

class AnonymousEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[AnonymousEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class AnonymousEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "anonymous_event_descriptor"
  def toKey(): String = name.name
}

class DevAnonymousEventLoggingRepo extends DevEventRepo[AnonymousEvent] with AnonymousEventLoggingRepo
