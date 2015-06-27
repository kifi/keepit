package com.keepit.model

import com.keepit.common.core._
import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import com.keepit.heimdal.{ EventType, EventDescriptor, MixpanelClient, AnonymousEvent }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.duration.Duration

trait AnonymousEventLoggingRepo extends EventRepo[AnonymousEvent]

class ProdAnonymousEventLoggingRepo(val mixpanel: MixpanelClient, val descriptors: AnonymousEventDescriptorRepo, protected val airbrake: AirbrakeNotifier)
    extends MongoEventRepo[AnonymousEvent] with AnonymousEventLoggingRepo {
  val warnBufferSize = 2000
  val maxBufferSize = 10000

}

trait AnonymousEventDescriptorRepo extends EventDescriptorRepo[AnonymousEvent]

class AnonymousEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[AnonymousEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class AnonymousEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "anonymous_event_descriptor"
  def toKey(): String = name.name
}

class DevAnonymousEventLoggingRepo extends DevEventRepo[AnonymousEvent] with AnonymousEventLoggingRepo
