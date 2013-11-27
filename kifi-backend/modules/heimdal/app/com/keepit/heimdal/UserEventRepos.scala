package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier


import reactivemongo.bson._
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import scala.concurrent.duration.Duration
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.shoebox.ShoeboxServiceClient
import reactivemongo.bson.BSONString
import reactivemongo.bson.BSONLong
import reactivemongo.api.collections.default.BSONCollection

trait UserEventLoggingRepo extends EventRepo[UserEvent] {
  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Unit
  def setUserProperties(userId: Id[User], properties: EventContext): Unit
  def delete(userId: Id[User]): Unit
  def fixKeepData(doIt : Boolean): Unit
}

class ProdUserEventLoggingRepo(
  val collection: BSONCollection,
  val mixpanel: MixpanelClient,
  val descriptors: UserEventDescriptorRepo,
  shoebox: ShoeboxServiceClient,
  protected val airbrake: AirbrakeNotifier)
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

  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Unit = mixpanel.incrementUserProperties(userId, increments)
  def setUserProperties(userId: Id[User], properties: EventContext): Unit = mixpanel.setUserProperties(userId, properties)
  def delete(userId: Id[User]): Unit = mixpanel.delete(userId)

  def fixKeepData(doIt : Boolean): Unit = {
    val documentsFuture = collection.find(BSONDocument("event_type" -> BSONString("keep"), "context.isPrivate" -> BSONArray(BSONDocument()))).cursor.toList()
    documentsFuture.foreach { documents =>
      println(documents.length)
      documents.foreach { doc =>
        val _id = doc.getAs[BSONObjectID]("_id").get
        val userId =  doc.getAs[BSONLong]("user_id").map(id => Id[User](id.value)).get
        val context = doc.getAs[BSONDocument]("context").get
        val url = context.getAs[BSONArray]("url").map(_.get(0).get) match { case Some(BSONString(str)) => str }
        shoebox.getNormalizedURIByURL(url) foreach { uriOpt =>
          uriOpt.foreach { uri =>
            shoebox.getBookmarkByUriAndUser(uri.id.get, userId) map { bookmarkOpt =>
              bookmarkOpt.map { bookmark =>
                collection.update(selector = BSONDocument("_id" -> _id), update = BSONDocument("$set" -> BSONDocument("context.isPrivate" -> BSONArray(BSONBoolean(bookmark.isPrivate)))))
              }
            }
          }
        }
      }
    }
  }
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
  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Unit = {}
  def setUserProperties(userId: Id[User], properties: EventContext): Unit = {}
  def delete(userId: Id[User]): Unit = {}
  def fixKeepData(doIt: Boolean): Unit = {}
}
