package com.keepit.heimdal

import reactivemongo.bson.{BSONDocument, BSONArray}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{Promise, Future}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext

abstract class SystemEventLoggingRepo extends MongoEventRepo[SystemEvent] {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: SystemEvent) : BSONDocument = BSONDocument(EventRepo.eventToBSONFields(event))
  def fromBSON(bson: BSONDocument): SystemEvent = ???
}

class ProdSystemEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends SystemEventLoggingRepo

class DevSystemEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends SystemEventLoggingRepo {
  override def insert(obj: SystemEvent, dropDups: Boolean = false) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}

trait SystemEventDescriptorRepo extends EventDescriptorRepo[SystemEvent]

class ProdSystemEventDescriptorRepo(val collection: BSONCollection, cache: SystemEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[SystemEvent] with SystemEventDescriptorRepo {
  override def upsert(obj: EventDescriptor[SystemEvent]) = super.upsert(obj) map { _ tap { _ => cache.set(SystemEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(SystemEventDescriptorNameKey(name)) { super.getByName(name) }
}

class SystemEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SystemEventDescriptorNameKey, EventDescriptor[SystemEvent]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class SystemEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor[SystemEvent]] {
  override val version = 1
  val namespace = "system_event_descriptor"
  def toKey(): String = name.name
}

class DevSystemEventDescriptorRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends DevEventDescriptorRepo[SystemEvent] with SystemEventDescriptorRepo

