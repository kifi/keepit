package com.keepit.model

import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import reactivemongo.bson.{ BSONDocument, BSONLong }
import reactivemongo.api.collections.default.BSONCollection
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import com.keepit.common.core._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.usersegment.UserSegment
import com.keepit.heimdal.{ HeimdalContext, UserEvent }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.heimdal._

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

  val warnBufferSize = 2000
  val maxBufferSize = 10000
  private val augmentors = Seq(
    UserIdAugmentor,
    new UserAugmentor(shoeboxClient),
    new ExtensionVersionAugmentor(shoeboxClient),
    new UserSegmentAugmentor(shoeboxClient),
    new UserValuesAugmentor(shoeboxClient),
    new UserKifiCampaignIdAugmentor(shoeboxClient))

  def toBSON(event: UserEvent): BSONDocument = {
    val userBatch: Long = event.userId.id / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    val fields = EventRepo.eventToBSONFields(event) ++ Seq(
      "userBatch" -> BSONLong(userBatch),
      "userId" -> BSONLong(event.userId.id)
    )
    BSONDocument(fields)
  }

  def fromBSON(bson: BSONDocument): UserEvent = ???

  def incrementUserProperties(userId: Id[User], increments: Map[String, Double]): Unit = mixpanel.incrementUserProperties(userId, increments)
  def setUserProperties(userId: Id[User], properties: HeimdalContext): Unit = mixpanel.setUserProperties(userId, properties)
  def delete(userId: Id[User]): Unit = mixpanel.delete(userId)
  def setUserAlias(userId: Id[User], externalId: ExternalId[User]): Unit = mixpanel.alias(userId, externalId)

  override def persist(userEvent: UserEvent): Future[Unit] =
    EventAugmentor.safelyAugmentContext(userEvent, augmentors: _*).flatMap { augmentedContext =>
      super.persist(userEvent.copy(context = augmentedContext))
    }
}

class ExtensionVersionAugmentor(shoeboxClient: ShoeboxServiceClient) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = userEvent.context.get[String]("extensionVersion").filter(_.nonEmpty).isEmpty
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    userEvent.context.data.get("kifiInstallationId") collect {
      case ContextStringData(id) => {
        shoeboxClient.getExtensionVersion(ExternalId[KifiInstallation](id)).map {
          version => Seq("extensionVersion" -> ContextStringData(version))
        }
      }
    } getOrElse Future.successful(Seq.empty)
  }
}

class UserValuesAugmentor(shoeboxClient: ShoeboxServiceClient) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = true
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    shoeboxClient.getUserValue(userEvent.userId, Gender.key).map(Seq(_).flatten.map { gender =>
      Gender.key.name -> ContextStringData(Gender(gender).toString)
    })
  }
}

class UserKifiCampaignIdAugmentor(shoeboxClient: ShoeboxServiceClient) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = true
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    shoeboxClient.getUserValue(userEvent.userId, UserValueName.KIFI_CAMPAIGN_ID).map { kcidOpt =>
      kcidOpt.map { kcid =>
        val segments = kcid.split("-")
        val segmentsWithNumber = segments zip (1 to segments.length)
        val res: Seq[(String, ContextData)] = ("kcid" -> ContextStringData(kcid)) +: segmentsWithNumber.map {
          case (segment, num) =>
            (s"kcid_$num" -> ContextStringData(segment))
        }
        res //type inference had some trouble here, hence this intermediate variable
      } getOrElse {
        Seq(("kcid" -> ContextStringData("unknown")))
      }
    }
  }
}

class UserSegmentAugmentor(shoeboxClient: ShoeboxServiceClient) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = true
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    val uid = userEvent.userId
    shoeboxClient.getUserSegment(uid).map { seg =>
      Seq(("userSegment" -> ContextStringData(UserSegment.getDescription(seg))))
    }
  }
}

object UserIdAugmentor extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = true
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = Future.successful(Seq("userId" -> ContextDoubleData(userEvent.userId.id)))
}

class UserAugmentor(shoeboxClient: ShoeboxServiceClient) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = {
    HeimdalContextBuilder.userFields.exists(!userEvent.context.data.contains(_))
  }

  def apply(userEvent: UserEvent) = shoeboxClient.getUser(userEvent.userId).map(_.toSeq.flatMap { user =>
    HeimdalContextBuilder.getUserFields(user)
  })
}

trait UserEventDescriptorRepo extends EventDescriptorRepo[UserEvent]

class ProdUserEventDescriptorRepo(val collection: BSONCollection, cache: UserEventDescriptorNameCache, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[UserEvent] with UserEventDescriptorRepo {
  override def upsert(obj: EventDescriptor) = super.upsert(obj) map { _ tap { _ => cache.set(UserEventDescriptorNameKey(obj.name), obj) } }
  override def getByName(name: EventType) = cache.getOrElseFutureOpt(UserEventDescriptorNameKey(name)) { super.getByName(name) }
}

class UserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

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
