package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier


import reactivemongo.bson.{BSONDocument, BSONLong}
import reactivemongo.api.collections.default.BSONCollection
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.User

trait UserEventLoggingRepo extends EventRepo[UserEvent] {
  def engage(user: User): Unit
}

class ProdUserEventLoggingRepo(val collection: BSONCollection, val mixpanel: MixpanelClient, val descriptors: UserEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
  extends MongoEventRepo[UserEvent] with UserEventLoggingRepo {

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
  def engage(user: User) = mixpanel.engage(user)
}

trait UserEventDescriptorRepo extends EventDescriptorRepo[UserEvent]

class ProdUserEventDescriptorRepo(val collection: BSONCollection, cache: UserEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[UserEvent] with UserEventDescriptorRepo {
  override def upsert(obj: EventDescriptor) = super.upsert(obj) map { _ tap { _ => cache.set(UserEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(UserEventDescriptorNameKey(name)) { super.getByName(name) }
}

class UserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class UserEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "user_event_descriptor"
  def toKey(): String = name.name
}

class DevUserEventLoggingRepo extends DevEventRepo[UserEvent] with UserEventLoggingRepo {
  def engage(user: User) = {}
}
