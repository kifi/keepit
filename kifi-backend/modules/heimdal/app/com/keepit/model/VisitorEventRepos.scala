package com.keepit.model

import com.keepit.common.core._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import com.keepit.heimdal.{ EventType, EventDescriptor, MixpanelClient, VisitorEvent }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration.Duration

trait VisitorEventLoggingRepo extends EventRepo[VisitorEvent]

class ProdVisitorEventLoggingRepo(val mixpanel: MixpanelClient, val descriptors: VisitorEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
    extends MongoEventRepo[VisitorEvent] with VisitorEventLoggingRepo {
  val warnBufferSize = 2000
  val maxBufferSize = 10000

}

trait VisitorEventDescriptorRepo extends EventDescriptorRepo[VisitorEvent]

class VisitorEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[VisitorEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class VisitorEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "visitor_event_descriptor"
  def toKey(): String = name.name
}

class DevVisitorEventLoggingRepo extends DevEventRepo[VisitorEvent] with VisitorEventLoggingRepo
