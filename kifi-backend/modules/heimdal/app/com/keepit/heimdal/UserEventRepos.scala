package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier


import reactivemongo.bson.{BSONDocument, BSONLong, BSONArray}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{Promise, Future}
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext

abstract class UserEventLoggingRepo extends MongoEventRepo[UserEvent] {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: UserEvent) : BSONDocument = {
    val userBatch: Long = event.userId / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    val fields = EventRepo.eventToBSONFields(event) ++ Seq(
      "user_batch" -> BSONLong(userBatch),
      "user_id" -> BSONLong(event.userId)
    )
    BSONDocument(fields)
  }

  def fromBSON(bson: BSONDocument): UserEvent = ???
}

class ProdUserEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends UserEventLoggingRepo

class DevUserEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends UserEventLoggingRepo {
  override def insert(obj: UserEvent, dropDups: Boolean = false) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}

trait UserEventDescriptorRepo extends EventDescriptorRepo[UserEvent]

class ProdUserEventDescriptorRepo(val collection: BSONCollection, cache: UserEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[UserEvent] with UserEventDescriptorRepo {
  override def upsert(obj: EventDescriptor[UserEvent]) = super.upsert(obj) map { _ tap { _ => cache.set(UserEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(UserEventDescriptorNameKey(name)) { super.getByName(name) }
}

class UserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserEventDescriptorNameKey, EventDescriptor[UserEvent]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class UserEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor[UserEvent]] {
  override val version = 1
  val namespace = "user_event_descriptor"
  def toKey(): String = name.name
}

class DevUserEventDescriptorRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends DevEventDescriptorRepo[UserEvent] with UserEventDescriptorRepo
