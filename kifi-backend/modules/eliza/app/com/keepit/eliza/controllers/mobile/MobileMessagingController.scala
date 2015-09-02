package com.keepit.eliza.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.BasicContact
import com.keepit.common.net.UserAgent
import com.keepit.common.time._
import com.keepit.eliza.commanders._
import com.keepit.eliza.model.{ Message, MessageSource, MessageThread }
import com.keepit.heimdal._
import com.keepit.model.{ Organization, User }
import com.keepit.social.BasicUserLikeEntity._
import com.keepit.social.{ BasicNonUser, BasicUser, BasicUserLikeEntity }

import scala.concurrent.ExecutionContext

//import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsObject, JsString, _ }

class MobileMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    basicMessageCommander: MessageFetchingCommander,
    notificationCommander: NotificationDeliveryCommander,
    val userActionsHelper: UserActionsHelper,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    messageSearchCommander: MessageSearchCommander,
    implicit val executionContext: ExecutionContext) extends UserActions with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true)
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }

  def getSystemNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true, filterByReplyable = Some(false))
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true, filterByReplyable = Some(false))
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId, filterByReplyable = Some(false))
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }

  def getMessageNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true, filterByReplyable = Some(true))
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true, filterByReplyable = Some(true))
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId, filterByReplyable = Some(true))
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }

  def getUnreadNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getUnreadSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationCommander.getLatestUnreadSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true).map(_._1)
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }

  def getSentNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSentSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = true)
      case None =>
        notificationCommander.getLatestSentSendableNotifications(request.userId, howMany.toInt, includeUriSummary = true)
    }
    noticesFuture.map { notices: Seq[com.keepit.eliza.commanders.NotificationJson] =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
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
      case (message, threadInfoOpt, messages) =>
        Ok(Json.obj(
          "id" -> message.externalId.id,
          "parentId" -> message.threadExtId.id,
          "createdAt" -> message.createdAt,
          "threadInfo" -> threadInfoOpt,
          "messages" -> messages.reverse))
    }
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = UserAction(parse.tolerantJson) { request =>
    val tStart = currentDateTime
    val o = request.body
    val (text, source) = (
      (o \ "text").as[String].trim,
      (o \ "source").asOpt[MessageSource]
    )
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "mobile")
    val (_, message) = messagingCommander.sendMessage(request.user.id.get, threadExtId, text, source, None)(contextBuilder.build)
    val tDiff = currentDateTime.getMillis - tStart.getMillis
    statsd.timing(s"messaging.replyMessage", tDiff, ONE_IN_HUNDRED)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }

  // todo(eishay, léo): the next version of this endpoint should rename the "uri" field to "url"
  def getPagedThread(threadId: String, pageSize: Int, fromMessageId: Option[String]) = UserAction.async { request =>
    basicMessageCommander.getThreadMessagesWithBasicUser(request.userId, ExternalId[MessageThread](threadId)) map {
      case (thread, allMsgs) =>
        val url = thread.url.getOrElse("") // needs to change when we have detached threads
        val nUrl = thread.nUrl.getOrElse("") // needs to change when we have detached threads
        val participants: Set[BasicUserLikeEntity] = allMsgs.map(_.participants).flatten.toSet
        val page = fromMessageId match {
          case None =>
            allMsgs.take(pageSize)
          case Some(idString) =>
            val id = ExternalId[Message](idString)
            val afterId = allMsgs.dropWhile(_.id != id)
            if (afterId.isEmpty) throw new IllegalStateException(s"thread of ${allMsgs.size} had no message id $id")
            afterId.drop(1).take(pageSize)
        }
        Ok(Json.obj(
          "id" -> threadId,
          "uri" -> url,
          "nUrl" -> nUrl,
          "participants" -> participants,
          "messages" -> (page map { m =>
            val adderAndAddedOpt: Option[(String, Seq[String])] = m.auxData match {
              case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => {
                Some(adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[JsValue]].map(json => (json \ "id").as[String]))
              }
              case _ => None
            }
            val baseJson = Json.obj(
              "id" -> m.id.toString,
              "time" -> m.createdAt.getMillis,
              "text" -> m.text
            )
            val msgJson = baseJson ++ (m.user match {
              case Some(bu: BasicUser) => Json.obj("userId" -> bu.externalId.toString)
              case Some(bnu: BasicNonUser) if bnu.kind.name == "email" => Json.obj("userId" -> bnu.id)
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
  }

  @deprecated(message = "use getPagedThread", since = "April 23, 2014")
  def getCompactThread(threadId: String) = UserAction.async { request =>
    basicMessageCommander.getThreadMessagesWithBasicUser(request.userId, ExternalId[MessageThread](threadId)) map {
      case (thread, msgs) =>
        val url = thread.url.getOrElse("") // needs to change when we have detached threads
        val nUrl = thread.nUrl.getOrElse("") // needs to change when we have detached threads
        val participants: Set[BasicUserLikeEntity] = msgs.map(_.participants).flatten.toSet
        Ok(Json.obj(
          "id" -> threadId,
          "uri" -> url,
          "nUrl" -> nUrl,
          "participants" -> participants,
          "messages" -> (msgs.reverse map { m =>
            val adderAndAddedOpt: Option[(String, Seq[String])] = m.auxData match {
              case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => {
                Some(adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[JsValue]].map(json => (json \ "id").as[String]))
              }
              case _ => None
            }
            val baseJson = Json.obj(
              "id" -> m.id.toString,
              "time" -> m.createdAt.getMillis,
              "text" -> m.text
            )
            val msgJson = baseJson ++ (m.user match {
              case Some(bu: BasicUser) => Json.obj("userId" -> bu.externalId.toString)
              case Some(bnu: BasicNonUser) if bnu.kind.name == "email" => Json.obj("userId" -> bnu.id)
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
  }

  def getThreadsByUrl(url: String) = UserAction.async { request =>
    messagingCommander.getThreadInfos(request.userId, url).map {
      case (_, threadInfos) =>
        Ok(Json.toJson(threadInfos))
    }
  }

  def hasThreadsByUrl(url: String) = UserAction.async { request =>
    messagingCommander.hasThreads(request.userId, url).map { yesorno =>
      Ok(Json.toJson(yesorno))
    }
  }

  def searchMessages(query: String, page: Int, storeInHistory: Boolean) = UserAction.async { request =>
    messageSearchCommander.searchMessages(request.userId, query, page, storeInHistory).map { notifs =>
      Ok(Json.toJson(notifs.map(_.obj)))
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

  def addParticipantsToThread(threadId: ExternalId[MessageThread], users: String, emailContacts: String) = UserAction { request =>
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
      messagingCommander.addParticipantsToThread(request.userId, threadId, validUserIds, validEmails, validOrgIds)(contextBuilder.build)
    }
    Ok("")
  }

  def getUnreadNotificationsCount = UserAction { request =>
    val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
    Ok(numUnreadUnmuted.toString)
  }

  def getUnreadCounts = UserAction { request =>
    val numUnreadNotifs = messagingCommander.getUnreadUnmutedThreadCount(request.userId, Some(false))
    val numUnreadMessages = messagingCommander.getUnreadUnmutedThreadCount(request.userId, Some(true))
    Ok(Json.obj(
      "messages" -> numUnreadMessages,
      "notifications" -> numUnreadNotifs
    ))
  }

  def markUnreadNotifications(kind: Option[String] = None) = UserAction { request =>
    kind match {
      case Some("system") =>
        notificationCommander.setSystemNotificationsRead(request.userId) // mark system notifs as read
      case Some("message") =>
        notificationCommander.setMessageNotificationsRead(request.userId) // mark message notifs as read
      case _ =>
        notificationCommander.setAllNotificationsRead(request.userId) // mark all as read
    }
    notificationCommander.notifyUnreadCount(request.userId)
    NoContent
  }
}
