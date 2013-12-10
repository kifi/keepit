package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier
import reactivemongo.bson.{BSONDocument, BSONLong}
import reactivemongo.api.collections.default.BSONCollection
import com.keepit.common.cache.{JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key}
import com.keepit.common.logging.AccessLog
import com.keepit.common.KestrelCombinator
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.{KifiInstallation, User}
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.shoebox.ShoeboxServiceClient
import scala.util.{Failure, Success}
import scala.concurrent.Future
import scala.concurrent.duration.Duration


trait UserEventLoggingRepo extends EventRepo[UserEvent] {
  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Unit
  def setUserProperties(userId: Id[User], properties: HeimdalContext): Unit
  def delete(userId: Id[User]): Unit
  def setUserAlias(userId: Id[User], externalId: ExternalId[User]): Unit
}

class ProdUserEventLoggingRepo(
  val collection: BSONCollection,
  val mixpanel: MixpanelClient,
  val descriptors: UserEventDescriptorRepo,
  shoeboxClient: ShoeboxServiceClient,
  protected val airbrake: AirbrakeNotifier)
  extends MongoEventRepo[UserEvent] with UserEventLoggingRepo {

  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: UserEvent) : BSONDocument = {
    val userBatch: Long = event.userId / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    val fields = EventRepo.eventToBSONFields(event) ++ Seq(
        "userBatch" -> BSONLong(userBatch),
        "userId" -> BSONLong(event.userId)
      )
    BSONDocument(fields)
  }

  def fromBSON(bson: BSONDocument): UserEvent = ???

  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Unit = mixpanel.incrementUserProperties(userId, increments)
  def setUserProperties(userId: Id[User], properties: HeimdalContext): Unit = mixpanel.setUserProperties(userId, properties)
  def delete(userId: Id[User]): Unit = mixpanel.delete(userId)
  def setUserAlias(userId: Id[User], externalId: ExternalId[User]): Unit = mixpanel.alias(userId, externalId)

  override def persist(userEvent: UserEvent) : Unit = {
    val augmentors = Seq(new ExtensionVersionAugmentor(shoeboxClient), new UserSegmentAugmentor(shoeboxClient))
    augmentUserEvent(userEvent, augmentors) onComplete {
      case Success(moreData) => {
        val oldContext = userEvent.context.data
        val newEvent = userEvent.copy(context = HeimdalContext(oldContext ++ moreData.toMap))
        super.persist(newEvent)
      }
      case Failure(_) => super.persist(userEvent)
    }
  }

  private def augmentUserEvent(userEvent: UserEvent, augmentors: Seq[UserEventAugmentor]): Future[Seq[(String, ContextData)]] = {
    val seqFuture = augmentors.map{ a => a.augment(userEvent) }
    Future.sequence(seqFuture).map{_.flatten}
  }
}

trait UserEventAugmentor {
  def augment(userEvent: UserEvent): Future[Seq[(String, ContextData)]]
}

class ExtensionVersionAugmentor(shoeboxClient: ShoeboxServiceClient) extends UserEventAugmentor {
  override def augment(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    val contextData = userEvent.context.data
    val default = Future.successful(Seq())
    contextData.get("extensionVersion") match {
      case None | Some(ContextStringData("")) => contextData.get("kifiInstallationId") match {
        case Some(ContextStringData(id)) => {
           shoeboxClient.getExtensionVersion(ExternalId[KifiInstallation](id)).map{
             version => Seq(("extensionVersion" -> ContextStringData(version)))
           } fallbackTo default
        }
        case _ => default
      }
      case _ => default
    }
  }
}

class UserSegmentAugmentor(shoeboxClient: ShoeboxServiceClient) extends UserEventAugmentor {
  override def augment(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    val contextData = userEvent.context.data
    val uid = Id[User](userEvent.userId)
    shoeboxClient.getUserSegment(uid).map{ seg =>
      Seq(("userSegment" -> ContextStringData(seg.description)))
    } fallbackTo Future.successful(Seq())
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
  def setUserProperties(userId: Id[User], properties: HeimdalContext): Unit = {}
  def delete(userId: Id[User]): Unit = {}
  def setUserAlias(userId: Id[User], externalId: ExternalId[User]) = {}
}
