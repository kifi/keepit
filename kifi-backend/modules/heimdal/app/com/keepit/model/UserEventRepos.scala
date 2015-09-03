package com.keepit.model

import com.keepit.commander.HelpRankCommander
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.{ CacheStatistics, FortyTwoCachePlugin, JsonCacheImpl, Key }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.{ Key, JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics }
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.logging.AccessLog
import com.keepit.common.core._
import com.keepit.eliza.ElizaServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.usersegment.UserSegment
import com.keepit.heimdal.{ HeimdalContext, UserEvent }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.heimdal._
import com.keepit.common.crypto.PublicIdConfiguration

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Success

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

class UserOrgValuesAugmentor(eventContextHelper: EventContextHelper) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = true
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    eventContextHelper.getPrimaryOrg(userEvent.userId).flatMap {
      case Some(orgId) => eventContextHelper.getOrgUserValues(orgId)
      case None => Future.successful(Seq.empty[(String, ContextData)])
    }
  }
}

class UserContentViewedAugmentor(eventContextHelper: EventContextHelper)(implicit val publicIdConfiguration: PublicIdConfiguration) extends EventAugmentor[UserEvent] {
  def isDefinedAt(userEvent: UserEvent) = userEvent.eventType == UserEventTypes.VIEWED_CONTENT
  def apply(userEvent: UserEvent): Future[Seq[(String, ContextData)]] = {
    val userId = userEvent.userId
    val orgIdOpt = userEvent.context.get[String]("orgId").flatMap(rawId =>
      Organization.decodePublicId(PublicId[Organization](rawId)).toOption
    )
    val libIdOpt = userEvent.context.get[String]("libraryId").flatMap(rawId =>
      Library.decodePublicId(PublicId[Library](rawId)).toOption
    )

    val orgPropsFut = orgIdOpt match {
      case Some(orgId) => eventContextHelper.getOrgEventValues(orgId, userId)
      case None => Future.successful(Seq.empty[(String, ContextData)])
    }
    val libPropsFut = libIdOpt match {
      case Some(libraryId) => eventContextHelper.getLibraryEventValues(libraryId, userId)
      case None => Future.successful(Seq.empty[(String, ContextData)])
    }

    for (orgProps <- orgPropsFut; libProps <- libPropsFut) yield orgProps ++ libProps
  }
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

class UserEventDescriptorNameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserEventDescriptorNameKey, EventDescriptor](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class UserEventDescriptorNameKey(name: EventType) extends Key[EventDescriptor] {
  override val version = 1
  val namespace = "user_event_descriptor"
  def toKey(): String = name.name
}
