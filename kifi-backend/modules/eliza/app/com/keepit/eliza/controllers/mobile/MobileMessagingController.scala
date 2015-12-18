package com.keepit.eliza.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.BasicContact
import com.keepit.common.net.{ URISanitizer, UserAgent }
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza.commanders._
import com.keepit.eliza.model.MessageSource
import com.keepit.heimdal._
import com.keepit.model.{ Keep, Organization, User }
import com.keepit.notify.model.Recipient
import com.keepit.social.BasicUserLikeEntity._
import com.keepit.social.{ BasicNonUser, BasicUser, BasicUserLikeEntity }
import com.kifi.macros.json

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Success

//import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsObject, JsString, _ }

class MobileMessagingController @Inject() (
    discussionCommander: ElizaDiscussionCommander,
    messagingCommander: MessagingCommander,
    basicMessageCommander: MessageFetchingCommander,
    notificationDeliveryCommander: NotificationDeliveryCommander,
    notificationCommander: NotificationCommander,
    val userActionsHelper: UserActionsHelper,
    notificationMessagingCommander: NotificationMessagingCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    messageSearchCommander: MessageSearchCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with ElizaServiceController {

  private def sanitizeUrls(obj: JsObject): JsObject = { // yolo
    val url = obj.value.get("url").collect { case JsString(raw) => JsString(URISanitizer.sanitize(raw)) }
    val nUrl = obj.value.get("nUrl").collect { case JsString(raw) => JsString(URISanitizer.sanitize(raw)) }
    var result = obj
    url.foreach(sanitized => result = (result - "url") + ("url" -> sanitized))
    nUrl.foreach(sanitized => result = (result - "nUrl") + ("nUrl" -> sanitized))
    result
  }

  def getNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val threadNoticesFuture = before match {
      case Some(before) =>
        notificationDeliveryCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationDeliveryCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true)
    }
    val noticesFuture = before match {
      case Some(before) =>
        notificationMessagingCommander.getLatestNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, true)
      case None =>
        notificationMessagingCommander.getLatestNotifications(request.userId, howMany.toInt, true).map(_.results)
    }
    for {
      threadNotices <- threadNoticesFuture
      notices <- noticesFuture
    } yield {
      val numUnreadUnmuted = notificationDeliveryCommander.getTotalUnreadUnmutedCount(request.userId)
      val notifications = notificationMessagingCommander.combineNotificationsWithThreads(threadNotices, notices, Some(howMany)).map(sanitizeUrls(_))
      Ok(Json.arr("notifications", notifications, numUnreadUnmuted))
    }
  }

  def getSystemNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationMessagingCommander.getLatestNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, true)
      case None =>
        notificationMessagingCommander.getLatestNotifications(request.userId, howMany.toInt, true).map(_.results)
    }
    for {
      notices <- noticesFuture
    } yield {
      val numUnreadUnmuted = notificationCommander.getUnreadNotificationsCount(Recipient(request.userId))
      val notifications = notificationMessagingCommander.combineNotificationsWithThreads(Seq(), notices, Some(howMany)).map(sanitizeUrls(_))
      Ok(Json.arr("notifications", notifications, numUnreadUnmuted))
    }
  }

  def getMessageNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationDeliveryCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationDeliveryCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true)
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      val notifications = notices.map(notif => sanitizeUrls(notif.obj))
      Ok(Json.arr("notifications", notifications, numUnreadUnmuted))
    }
  }

  def getUnreadNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val threadNoticesFuture = before match {
      case Some(before) =>
        notificationDeliveryCommander.getUnreadSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationDeliveryCommander.getLatestUnreadSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true).map(_._1)
    }
    val noticesFuture = before match {
      case Some(before) =>
        notificationMessagingCommander.getNotificationsWithNewEventsBefore(request.userId, parseStandardTime(before), howMany.toInt, true)
      case None =>
        notificationMessagingCommander.getNotificationsWithNewEvents(request.userId, howMany.toInt, true).map(_.results)
    }
    for {
      threadNotices <- threadNoticesFuture
      notices <- noticesFuture
    } yield {
      val numUnreadUnmuted = notificationDeliveryCommander.getTotalUnreadUnmutedCount(request.userId)
      val notifications = notificationMessagingCommander.combineNotificationsWithThreads(threadNotices, notices, Some(howMany)).map(sanitizeUrls(_))
      Ok(Json.arr("notifications", notifications, numUnreadUnmuted))
    }
  }

  def getSentNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationDeliveryCommander.getSentSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationDeliveryCommander.getLatestSentSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true)
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = notificationDeliveryCommander.getTotalUnreadUnmutedCount(request.userId)
      val notifications = notices.map(notif => sanitizeUrls(notif.obj))
      Ok(Json.arr("notifications", notifications, numUnreadUnmuted))
    }
  }

  def sendMessageAction() = UserAction.async(parse.tolerantJson) { request =>
    val o = request.body
    val (title, text, source) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim,
      (o \ "source").asOpt[MessageSource]
    )
    val (validUserRecipients, validEmailRecipients, validOrgRecipients) = messagingCommander.parseRecipients((o \ "recipients").as[Seq[JsValue]]) // XXXX
    val url = (o \ "url").as[String]

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "mobile")

    messagingCommander.sendMessageAction(title, text, source, validUserRecipients, validEmailRecipients, validOrgRecipients, url, request.userId, contextBuilder.build).map {
      case (message, threadInfo, messages) =>
        Ok(Json.obj(
          "id" -> message.pubId,
          "parentId" -> threadInfo.keepId,
          "createdAt" -> message.createdAt,
          "threadInfo" -> ElizaThreadInfo.writesThreadInfo.writes(threadInfo.copy(url = URISanitizer.sanitize(threadInfo.url), nUrl = threadInfo.nUrl.map(URISanitizer.sanitize))),
          "messages" -> messages.reverse.map(message => message.copy(url = URISanitizer.sanitize(message.url)))))
    }.recover {
      case ex: Exception if ex.getMessage == "insufficient_org_permissions" =>
        Forbidden(Json.obj("error" -> "insufficient_org_permissions"))
    }
  }

  def sendMessageReplyAction(pubKeepId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    Keep.decodePublicId(pubKeepId) match {
      case Success(keepId) =>
        val tStart = currentDateTime
        val o = request.body
        val (text, source) = (
          (o \ "text").as[String].trim,
          (o \ "source").asOpt[MessageSource]
        )
        val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
        contextBuilder += ("source", "mobile")
        discussionCommander.sendMessage(request.user.id.get, text, keepId, source)(contextBuilder.build).map { message =>
          val tDiff = currentDateTime.getMillis - tStart.getMillis
          statsd.timing(s"messaging.replyMessage", tDiff, ONE_IN_HUNDRED)
          Ok(Json.obj("id" -> message.pubId, "parentId" -> pubKeepId, "createdAt" -> message.sentAt))
        }
      case _ => Future.successful(BadRequest("invalid_id"))
    }
  }

  // todo(eishay, léo): the next version of this endpoint should rename the "uri" field to "url"
  def getPagedThread(pubKeepId: PublicId[Keep], pageSize: Int, fromMessageId: Option[String]) = UserAction.async { request =>
    Keep.decodePublicId(pubKeepId) match {
      case Success(keepId) =>
        basicMessageCommander.getDiscussionAndKeep(request.userId, keepId) map {
          case (discussion, keep) =>
            val url = URISanitizer.sanitize(discussion.url)
            val nUrl = URISanitizer.sanitize(discussion.nUrl)
            val participants: Set[BasicUserLikeEntity] = discussion.participants
            val page = fromMessageId match {
              case None => discussion.messages.take(pageSize)
              case Some(idString) =>
                val publicId = basicMessageCommander.getMessagePublicId(idString)
                val afterId = discussion.messages.dropWhile(_.id != publicId)
                if (afterId.isEmpty) throw new IllegalStateException(s"thread of ${discussion.messages.size} had no message id $publicId")
                afterId.drop(1).take(pageSize)
            }
            Ok(Json.obj(
              "id" -> pubKeepId,
              "uri" -> url,
              "nUrl" -> nUrl,
              "keep" -> keep,
              "participants" -> participants,
              "messages" -> (page map { m =>
                val adderAndAddedOpt: Option[(String, Seq[String])] = m.auxData match {
                  case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => {
                    Some(adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[JsValue]].map(json => (json \ "id").as[String]))
                  }
                  case _ => None
                }
                val baseJson = Json.obj(
                  "id" -> m.id,
                  "time" -> m.createdAt.getMillis,
                  "text" -> m.text
                )
                val msgJson = baseJson ++ (m.user match {
                  case Some(BasicUserLikeEntity.user(bu)) => Json.obj("userId" -> bu.externalId.toString)
                  case Some(BasicUserLikeEntity.nonUser(bnu)) if bnu.kind.name == "email" => Json.obj("userId" -> bnu.id)
                  case _ => Json.obj()
                })

                adderAndAddedOpt.map { adderAndAdded =>
                  msgJson.deepMerge(Json.obj("added" -> adderAndAdded._2, "userId" -> adderAndAdded._1))
                } getOrElse {
                  msgJson
                }
              })
            ))
        }
      case _ => Future.successful(BadRequest("invalid_keep_id"))
    }
  }

  @deprecated(message = "use getPagedThread", since = "April 23, 2014")
  def getCompactThread(pubKeepId: PublicId[Keep]) = UserAction.async { request =>
    Keep.decodePublicId(pubKeepId) match {
      case Success(keepId) =>
        basicMessageCommander.getDiscussionAndKeep(request.userId, keepId) map {
          case (discussion, keep) =>
            val url = URISanitizer.sanitize(discussion.url)
            val nUrl = URISanitizer.sanitize(discussion.nUrl)
            val participants: Set[BasicUserLikeEntity] = discussion.participants
            Ok(Json.obj(
              "id" -> pubKeepId,
              "uri" -> url,
              "nUrl" -> nUrl,
              "keep" -> keep,
              "participants" -> participants,
              "messages" -> (discussion.messages.reverse map { m =>
                val adderAndAddedOpt: Option[(String, Seq[String])] = m.auxData match {
                  case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => {
                    Some(adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[JsValue]].map(json => (json \ "id").as[String]))
                  }
                  case _ => None
                }
                val baseJson = Json.obj(
                  "id" -> m.id,
                  "time" -> m.createdAt.getMillis,
                  "text" -> m.text
                )
                val msgJson = baseJson ++ (m.user match {
                  case Some(BasicUserLikeEntity.user(bu)) => Json.obj("userId" -> bu.externalId.toString)
                  case Some(BasicUserLikeEntity.nonUser(bnu)) if bnu.kind.name == "email" => Json.obj("userId" -> bnu.id)
                  case _ => Json.obj()
                })

                adderAndAddedOpt.map { adderAndAdded =>
                  msgJson.deepMerge(Json.obj("added" -> adderAndAdded._2, "userId" -> adderAndAdded._1))
                } getOrElse {
                  msgJson
                }
              })
            ))
        }
      case _ => Future.successful(BadRequest("invalid_keep_id"))
    }
  }

  def getThreadsByUrl(url: String) = UserAction.async { request =>
    messagingCommander.getThreadInfos(request.userId, url).map {
      case (_, threadInfos) =>
        Ok(Json.toJson(threadInfos.map(info => info.copy(url = URISanitizer.sanitize(info.url), nUrl = info.nUrl.map(URISanitizer.sanitize)))))
    }
  }

  def hasThreadsByUrl(url: String) = UserAction.async { request =>
    messagingCommander.hasThreads(request.userId, url).map { yesorno =>
      Ok(Json.toJson(yesorno))
    }
  }

  def searchMessages(query: String, page: Int, storeInHistory: Boolean) = UserAction.async { request =>
    messageSearchCommander.searchMessages(request.userId, query, page, storeInHistory).map { notifs =>
      val notifications = notifs.map(notif => sanitizeUrls(notif.obj))
      Ok(Json.toJson(notifications))
    }
  }

  def getMessageSearchHistory() = UserAction { request =>
    val (queries, emails, optOut) = messageSearchCommander.getHistory(request.userId)
    Ok(Json.obj("qs" -> queries, "es" -> emails, "optOut" -> optOut))
  }

  def getMessageSearchHistoryOptOut() = UserAction { request =>
    Ok(Json.obj("didOptOut" -> messageSearchCommander.getHistoryOptOut(request.userId)))
  }

  def setMessageSearchHistoryOptOut() = UserAction(parse.tolerantJson) { request =>
    Ok(Json.obj("didOptOut" -> messageSearchCommander.setHistoryOptOut(request.userId, (request.body \ "optOut").as[Boolean])))
  }

  def clearMessageSearchHistory() = UserAction { request =>
    messageSearchCommander.clearHistory(request.userId)
    Ok("")
  }

  // users is a string because orgs come in through that field
  @json case class AddParticipants(users: Seq[String], emails: Seq[BasicContact])
  def addParticipantsToThreadV2(pubKeepId: PublicId[Keep]) = UserAction(parse.tolerantJson) { request =>
    (Keep.decodePublicId(pubKeepId), request.body.asOpt[AddParticipants]) match {
      case (Success(keepId), Some(addReq)) =>
        val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
        contextBuilder += ("source", "mobile")

        val (validUserIds, validOrgIds) = addReq.users.foldRight((Seq[ExternalId[User]](), Seq[PublicId[Organization]]())) { (id, acc) =>
          ExternalId.asOpt[User](id) match {
            case Some(userId) if userId.id.length == 36 => (acc._1 :+ userId, acc._2)
            case None if id.startsWith("o") => (acc._1, acc._2 :+ PublicId[Organization](id))
          }
        }
        discussionCommander.addParticipantsToThread(request.userId, keepId, validUserIds, addReq.emails, validOrgIds)(contextBuilder.build)
        Ok
      case _ => BadRequest
    }
  }

  // Do not use, clients have moved to addParticipantsToThreadV2 which doesn't have an insane API
  def addParticipantsToThread(pubKeepId: PublicId[Keep], users: String, emailContacts: String) = UserAction { request =>
    Keep.decodePublicId(pubKeepId) match {
      case Success(keepId) =>
        if (users.nonEmpty || emailContacts.nonEmpty) {
          val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
          contextBuilder += ("source", "mobile")
          //assuming the client does the proper checks!
          val validEmails = emailContacts.split(",").map(_.trim).filterNot(_.isEmpty).map { email => BasicContact.fromString(email).get }
          val (validUserIds, validOrgIds) = users.split(",").map(_.trim).filterNot(_.isEmpty).foldRight((Seq[ExternalId[User]](), Seq[PublicId[Organization]]())) { (id, acc) =>
            // This API is basically insane. Passing comma separated entities in URL params on a POST?
            ExternalId.asOpt[User](id) match {
              case Some(userId) if userId.id.length == 36 => (acc._1 :+ userId, acc._2)
              case None if id.startsWith("o") => (acc._1, acc._2 :+ PublicId[Organization](id))
            }
          }
          discussionCommander.addParticipantsToThread(request.userId, keepId, validUserIds, validEmails, validOrgIds)(contextBuilder.build)
        }
        Ok("")
      case _ => BadRequest("invalid_keep_id")
    }
  }

  def getUnreadNotificationsCount = UserAction { request =>
    val numUnreadUnmuted = notificationDeliveryCommander.getTotalUnreadUnmutedCount(request.userId)
    Ok(numUnreadUnmuted.toString)
  }

  def getUnreadCounts = UserAction { request =>
    val numUnreadNotifs = notificationCommander.getUnreadNotificationsCount(Recipient(request.userId))
    val numUnreadMessages = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
    Ok(Json.obj(
      "messages" -> numUnreadMessages,
      "notifications" -> numUnreadNotifs
    ))
  }

  def markUnreadNotifications(kind: Option[String] = None) = UserAction { request =>
    kind match {
      case Some("system") =>
        notificationDeliveryCommander.setSystemNotificationsRead(request.userId) // mark system notifs as read
      case Some("message") =>
        notificationDeliveryCommander.setMessageNotificationsRead(request.userId) // mark message notifs as read
      case _ =>
        notificationDeliveryCommander.setAllNotificationsRead(request.userId) // mark all as read
    }
    notificationDeliveryCommander.notifyUnreadCount(request.userId)
    NoContent
  }
}
