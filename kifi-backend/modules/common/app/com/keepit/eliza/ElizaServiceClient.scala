package com.keepit.eliza

import com.google.inject.Inject
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import com.keepit.common.core._
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.json.TraversableFormat
import com.keepit.common.json.TupleFormat._
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.net.{CallTimeouts, HttpClient}
import com.keepit.common.routes.Eliza
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.store.S3UserPictureConfig
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.discussion.{CrossServiceMessage, Discussion, Message}
import com.keepit.eliza.model._
import com.keepit.model._
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.notify.model.{GroupingNotificationKind, Recipient}
import com.keepit.search.index.message.ThreadContent
import com.keepit.social.BasicNonUser
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.eliza.ElizaServiceClient._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

sealed case class LibraryPushNotificationCategory(name: String)
sealed case class UserPushNotificationCategory(name: String)
sealed case class SimplePushNotificationCategory(name: String)
sealed case class OrgPushNotificationCategory(name: String)

object SimplePushNotificationCategory {
  val PersonaUpdate = SimplePushNotificationCategory("PersonaUpdate")
  val HailMerryUpdate = SimplePushNotificationCategory("HailMerryUpdate")
  val OrganizationInvitation = SimplePushNotificationCategory("OrganizationInvitation")
}

object UserPushNotificationCategory {
  val UserConnectionRequest = UserPushNotificationCategory("UserConnectionRequest")
  val UserConnectionAccepted = UserPushNotificationCategory("UserConnectionAccepted")
  val ContactJoined = UserPushNotificationCategory("ContactJoined")
  val NewLibraryFollower = UserPushNotificationCategory("NewLibraryFollower")
  val NewLibraryCollaborator = UserPushNotificationCategory("NewLibraryCollaborator")
  val LibraryInviteAccepted = UserPushNotificationCategory("LibraryInviteAccepted")
  val NewOrganizationMember = UserPushNotificationCategory("NewOrganizationMember")
}

object LibraryPushNotificationCategory {
  val LibraryChanged = LibraryPushNotificationCategory("LibraryChanged")
  val LibraryInvitation = LibraryPushNotificationCategory("LibraryInvitation")
}

object OrgPushNotificationCategory {

  val OrganizationInvitation = OrgPushNotificationCategory("OrganizationInvitation")

  implicit val format = new Format[OrgPushNotificationCategory] {
    def reads(json: JsValue) = json.as[String] match {
      case OrganizationInvitation.name => JsSuccess(OrganizationInvitation)
    }
    def writes(o: OrgPushNotificationCategory) = JsString(o.name)
  }
}

case class PushNotificationExperiment(name: String)
object PushNotificationExperiment {
  val Experiment1 = PushNotificationExperiment("Experiment1")
  val Experiment2 = PushNotificationExperiment("Experiment2")
  val All = Seq(Experiment1, Experiment2)
  implicit val format = Json.format[PushNotificationExperiment]
}

case class OrgPushNotificationRequest( // pretty bare right now, add org-specific fields as needed
  userId: Id[User],
  message: String,
  pushNotificationExperiment: PushNotificationExperiment,
  category: OrgPushNotificationCategory,
  force: Boolean = false)
object OrgPushNotificationRequest {
  implicit val format: Format[OrgPushNotificationRequest] = (
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'message).format[String] and
    (__ \ 'pushNotificationExperiment).format[PushNotificationExperiment] and
    (__ \ 'category).format[OrgPushNotificationCategory] and
    (__ \ 'force).format[Boolean]
  )(OrgPushNotificationRequest.apply, unlift(OrgPushNotificationRequest.unapply))
}

trait ElizaServiceClient extends ServiceClient {
  final val serviceType = ServiceType.ELIZA
  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Future[Unit]
  def sendToUser(userId: Id[User], data: JsArray): Future[Unit]
  def sendToAllUsers(data: JsArray): Unit

  def flush(userId: Id[User]): Future[Unit]

  def sendUserPushNotification(userId: Id[User], message: String, recipient: User, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int]
  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory, force: Boolean = false): Future[Int]
  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory, force: Boolean = false): Future[Int]
  def sendOrgPushNotification(request: OrgPushNotificationRequest): Future[Int]

  def connectedClientCount: Future[Seq[Int]]
  def sendNotificationEvent(event: NotificationEvent): Future[Unit]
  def completeNotification[N <: NotificationEvent, G](kind: GroupingNotificationKind[N, G], params: G, recipient: Recipient): Future[Boolean]
  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]]
  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats]
  def getNonUserThreadMuteInfo(publicId: String): Future[Option[(String, Boolean)]]
  def setNonUserThreadMuteState(publicId: String, muted: Boolean): Future[Boolean]
  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Seq[Id[User]]]
  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]]

  //migration
  def importThread(data: JsObject): Unit

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]]
  def getUnreadNotifications(userId: Id[User], howMany: Int): Future[Seq[UserThreadView]]
  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]]): Future[Seq[GroupThreadStats]]
  def getAllThreadsForGroupByWeek(users: Seq[Id[User]]): Future[Seq[GroupThreadStats]]
  def getParticipantsByThreadExtId(threadExtId: String): Future[Set[Id[User]]]

  // Discussion cross-service methods
  def getCrossServiceMessages(msgIds: Set[Id[Message]]): Future[Map[Id[Message], CrossServiceMessage]]
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]]
  def markKeepsAsReadForUser(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]]
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep]): Future[Message]
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[Message]], limit: Int): Future[Seq[Message]]
  def editMessage(msgId: Id[Message], newText: String): Future[Message]
  def deleteMessage(msgId: Id[Message]): Future[Unit]

  def keepHasThreadWithAccessToken(keepId: Id[Keep], accessToken: String): Future[Boolean]
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]]): Future[Set[Id[User]]]
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]]): Future[Unit]
  def getMessagesChanged(seqNum: SequenceNumber[Message], fetchSize: Int): Future[Seq[CrossServiceMessage]]
  def convertNonUserThreadToUserThread(userId: Id[User], accessToken: String): Future[(Option[EmailAddress], Option[Id[User]])]
}

class ElizaServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster,
  implicit val defaultContext: ExecutionContext,
  userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache)
    extends ElizaServiceClient with Logging {

  def sendUserPushNotification(userId: Id[User], message: String, recipient: User, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int] = {
    val payload = Json.obj("userId" -> userId, "message" -> message, "recipientId" -> recipient.externalId, "username" -> recipient.username.value, "pictureUrl" -> Json.toJson(recipient.pictureName.getOrElse(S3UserPictureConfig.defaultName)), "pushNotificationExperiment" -> pushNotificationExperiment, "category" -> category.name)
    call(Eliza.internal.sendUserPushNotification(), payload, callTimeouts = longTimeout).map { response =>
      response.body.toInt
    }
  }

  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory, force: Boolean = false): Future[Int] = {
    val payload = Json.obj("userId" -> userId, "message" -> message, "libraryId" -> libraryId, "libraryUrl" -> libraryUrl, "pushNotificationExperiment" -> pushNotificationExperiment, "category" -> category.name, "force" -> force)
    call(Eliza.internal.sendLibraryPushNotification, payload, callTimeouts = longTimeout).map { response =>
      response.body.toInt
    }
  }

  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory, force: Boolean = false): Future[Int] = {
    val payload = Json.obj("userId" -> userId, "message" -> message, "pushNotificationExperiment" -> pushNotificationExperiment, "category" -> category.name, "force" -> force)
    call(Eliza.internal.sendGeneralPushNotification, payload, callTimeouts = longTimeout).map { response =>
      response.body.toInt
    }
  }

  def sendOrgPushNotification(request: OrgPushNotificationRequest): Future[Int] = {
    call(Eliza.internal.sendOrgPushNotification, Json.toJson(request), callTimeouts = longTimeout).map(response => response.body.toInt)
  }

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Future[Unit] = {
    val payload = Json.obj("userId" -> userId, "data" -> data)
    Future.sequence(broadcast(Eliza.internal.sendToUserNoBroadcast, payload).values).map(_ => ())
  }

  def sendToUser(userId: Id[User], data: JsArray): Future[Unit] = {
    val payload = Json.obj("userId" -> userId, "data" -> data)
    call(Eliza.internal.sendToUser, payload).map(_ => ())
  }

  def sendToAllUsers(data: JsArray): Unit = {
    broadcast(Eliza.internal.sendToAllUsers, data)
  }

  def flush(userId: Id[User]): Future[Unit] = {
    val payload = Json.obj("userId" -> userId, "data" -> Json.arr("flush"))
    call(Eliza.internal.sendToUser, payload).map(_ => ())
  }

  def connectedClientCount: Future[Seq[Int]] = {
    Future.sequence(broadcast(Eliza.internal.connectedClientCount).values.toSeq).map { respSeq =>
      respSeq.map { resp => resp.body.toInt }
    }
  }

  val longTimeout = CallTimeouts(responseTimeout = Some(10000), maxWaitTime = Some(10000), maxJsonParseTime = Some(10000))

  def sendNotificationEvent(event: NotificationEvent): Future[Unit] = {
    val payload = Json.toJson(event)
    call(Eliza.internal.sendNotificationEvent(), payload).imap(_ => ())
  }

  def completeNotification[N <: NotificationEvent, G](kind: GroupingNotificationKind[N, G], params: G, recipient: Recipient): Future[Boolean] = {
    val groupIdentifier = kind.gid.serialize(params)
    val payload = Json.obj(
      "recipient" -> recipient,
      "kind" -> kind,
      "groupIdentifier" -> groupIdentifier
    )
    call(Eliza.internal.completeNotification(), payload).map { resp =>
       Json.parse(resp.body).as[Boolean]
    }
  }

  def getThreadContentForIndexing(sequenceNumber: SequenceNumber[ThreadContent], maxBatchSize: Long): Future[Seq[ThreadContent]] = {
    call(Eliza.internal.getThreadContentForIndexing(sequenceNumber, maxBatchSize), callTimeouts = longTimeout)
      .map { response =>
        val json = Json.parse(response.body).as[JsArray]
        json.value.map(_.as[ThreadContent])
      }
  }

  def getUserThreadStats(userId: Id[User]): Future[UserThreadStats] = {
    userThreadStatsForUserIdCache.get(UserThreadStatsForUserIdKey(userId)) map { s => Future.successful(s) } getOrElse {
      call(Eliza.internal.getUserThreadStats(userId)).map { response =>
        Json.parse(response.body).as[UserThreadStats]
      }
    }
  }

  def getNonUserThreadMuteInfo(publicId: String): Future[Option[(String, Boolean)]] = {
    call(Eliza.internal.getNonUserThreadMuteInfo(publicId), callTimeouts = longTimeout).map { response =>
      Json.parse(response.body).asOpt[(String, Boolean)]
    }
  }

  def setNonUserThreadMuteState(publicId: String, muted: Boolean): Future[Boolean] = {
    call(Eliza.internal.setNonUserThreadMuteState(publicId, muted), callTimeouts = longTimeout).map { response =>
      Json.parse(response.body).as[Boolean]
    }
  }

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    call(Eliza.internal.checkUrisDiscussed(userId), body = Json.toJson(uriIds), attempts = 2, callTimeouts = longTimeout).map { r =>
      r.json.as[Seq[Boolean]]
    }
  }

  //migration
  def importThread(data: JsObject): Unit = {
    call(Eliza.internal.importThread, data)
  }

  def getRenormalizationSequenceNumber(): Future[SequenceNumber[ChangedURI]] = call(Eliza.internal.getRenormalizationSequenceNumber).map(_.json.as(SequenceNumber.format[ChangedURI]))

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Seq[Id[User]]] = {
    call(Eliza.internal.keepAttribution(userId, uriId)).map { response =>
      Json.parse(response.body).as[Seq[Id[User]]]
    }
  }

  def getUnreadNotifications(userId: Id[User], howMany: Int): Future[Seq[UserThreadView]] = {
    call(Eliza.internal.getUnreadNotifications(userId, howMany)).map { response =>
      Json.parse(response.body).as[Seq[UserThreadView]]
    }
  }

  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]]): Future[Seq[GroupThreadStats]] = {
    call(Eliza.internal.getSharedThreadsForGroupByWeek, body = Json.toJson(users)).map { response =>
      response.json.as[Seq[GroupThreadStats]]
    }
  }

  def getAllThreadsForGroupByWeek(users: Seq[Id[User]]): Future[Seq[GroupThreadStats]] = {
    call(Eliza.internal.getAllThreadsForGroupByWeek, body = Json.toJson(users)).map { response =>
      response.json.as[Seq[GroupThreadStats]]
    }
  }

  def getParticipantsByThreadExtId(threadExtId: String): Future[Set[Id[User]]] = {
    call(Eliza.internal.getParticipantsByThreadExtId(threadExtId)).map { response =>
      response.json.as[Set[Id[User]]]
    }
  }

  def getCrossServiceMessages(msgIds: Set[Id[Message]]): Future[Map[Id[Message], CrossServiceMessage]] = {
    import GetCrossServiceMessages._
    val request = Request(msgIds)
    val x = implicitly[Format[Request]]
    call(Eliza.internal.getCrossServiceMessages, body = Json.toJson(request)).map { response =>
      response.json.as[Response].msgs
    }
  }
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]] = {
    import GetDiscussionsForKeeps._
    val request = Request(keepIds)
    call(Eliza.internal.getDiscussionsForKeeps, body = Json.toJson(request)).map { response =>
      response.json.as[Response].discussions
    }
  }

  def markKeepsAsReadForUser(userId: Id[User], lastSeenByKeep: Map[Id[Keep], Id[Message]]): Future[Map[Id[Keep], Int]] = {
    import MarkKeepsAsReadForUser._
    val request = Request(userId, lastSeenByKeep)
    call(Eliza.internal.markKeepsAsReadForUser(), body = Json.toJson(request)).map { response =>
      response.json.as[Response].unreadCount
    }
  }
  def sendMessageOnKeep(userId: Id[User], text: String, keepId: Id[Keep]): Future[Message] = {
    import SendMessageOnKeep._
    val request = Request(userId, text, keepId)
    call(Eliza.internal.sendMessageOnKeep(), body = Json.toJson(request)).map { response =>
      response.json.as[Response].msg
    }
  }
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[Message]], limit: Int): Future[Seq[Message]] = {
    import GetMessagesOnKeep._
    val request = Request(keepId, fromIdOpt, limit)
    call(Eliza.internal.getMessagesOnKeep, body = Json.toJson(request)).map { response =>
      response.json.as[Response].msgs
    }
  }
  def editMessage(msgId: Id[Message], newText: String): Future[Message] = {
    import EditMessage._
    val request = Request(msgId, newText)
    call(Eliza.internal.editMessage(), body = Json.toJson(request)).map { response =>
      response.json.as[Response].msg
    }
  }
  def deleteMessage(msgId: Id[Message]): Future[Unit] = {
    import DeleteMessage._
    val request = Request(msgId)
    call(Eliza.internal.deleteMessage(), body = Json.toJson(request)).map { response =>
      Unit
    }
  }
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]]): Future[Set[Id[User]]] = {
    import EditParticipantsOnKeep._
    val request = Request(keepId, editor, newUsers)
    call(Eliza.internal.editParticipantsOnKeep(), body = Json.toJson(request)).map { response =>
      response.json.as[Response].users
    }
  }
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]]): Future[Unit] = {
    import DeleteThreadsForKeeps._
    val request = Request(keepIds)
    call(Eliza.internal.deleteThreadsForKeeps(), body = Json.toJson(request)).map { response =>
      Unit
    }
  }

  def keepHasThreadWithAccessToken(keepId: Id[Keep], accessToken: String): Future[Boolean] = {
    call(Eliza.internal.keepHasAccessToken(keepId, accessToken)).map { response =>
      (response.json \ "hasToken").as[Boolean]
    }
  }
  def getMessagesChanged(seqNum: SequenceNumber[Message], fetchSize: Int): Future[Seq[CrossServiceMessage]] = {
    call(Eliza.internal.getMessagesChanged(seqNum, fetchSize)).map { _.json.as[Seq[CrossServiceMessage]] }
  }
  def convertNonUserThreadToUserThread(userId: Id[User], accessToken: String): Future[(Option[EmailAddress], Option[Id[User]])] = {
    call(Eliza.internal.convertNonUserThreadToUserThread(userId: Id[User], accessToken: String)).map { res =>
      val emailAddressOpt = (res.json \ "emailAddress").asOpt[EmailAddress] // none if no email found
      val addedByOpt = (res.json \ "addedBy").asOpt[Id[User]] // none if no NonUserThread found for accessToken
      (emailAddressOpt, addedByOpt)
    }
  }
}

object ElizaServiceClient {
  object GetCrossServiceMessages {
    case class Request(msgIds: Set[Id[Message]])
    case class Response(msgs: Map[Id[Message], CrossServiceMessage])
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object GetDiscussionsForKeeps {
    case class Request(keepIds: Set[Id[Keep]])
    case class Response(discussions: Map[Id[Keep], Discussion])
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object MarkKeepsAsReadForUser {
    case class Request(userId: Id[User], lastSeen: Map[Id[Keep], Id[Message]])
    case class Response(unreadCount: Map[Id[Keep], Int])
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object SendMessageOnKeep {
    case class Request(userId: Id[User], text: String, keepId: Id[Keep])
    case class Response(msg: Message)
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object GetMessagesOnKeep {
    case class Request(keepId: Id[Keep], fromIdOpt: Option[Id[Message]], limit: Int)
    case class Response(msgs: Seq[Message])
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object EditMessage {
    case class Request(msgId: Id[Message], newText: String)
    case class Response(msg: Message)
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object DeleteMessage {
    case class Request(msgId: Id[Message])
    implicit val requestFormat: Format[Request] = Format(
      Reads { j => j.validate[Long].map(n => Request(Id(n))) },
      Writes { o => JsNumber(o.msgId.id) }
    )
  }
  object EditParticipantsOnKeep {
    case class Request(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]])
    case class Response(users: Set[Id[User]])
    implicit val requestFormat: Format[Request] = Json.format[Request]
    implicit val responseFormat: Format[Response] = Json.format[Response]
  }
  object DeleteThreadsForKeeps {
    case class Request(keepIds: Set[Id[Keep]])
    implicit val requestFormat: Format[Request] = Json.format[Request]
  }
}
