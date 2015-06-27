package com.keepit.model

import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import com.keepit.heimdal.SystemEvent
import com.keepit.heimdal._

import scala.concurrent.duration.Duration
import com.keepit.common.core._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

trait SystemEventLoggingRepo extends EventRepo[SystemEvent]

class ProdSystemEventLoggingRepo(val mixpanel: MixpanelClient, val descriptors: SystemEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
    extends MongoEventRepo[SystemEvent] with SystemEventLoggingRepo {
}

trait SystemEventDescriptorRepo extends EventDescriptorRepo[SystemEvent]

class SystemEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[SystemEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class SystemEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "system_event_descriptor"
  def toKey(): String = name.name
}

class DevSystemEventLoggingRepo extends DevEventRepo[SystemEvent] with SystemEventLoggingRepo
