package com.keepit.eliza.controllers.mobile

import com.keepit.social.BasicUserLikeEntity._
import com.keepit.eliza.commanders._
import com.keepit.eliza.model.{MessageSource, Message, MessageThread}
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.time._
import com.keepit.heimdal._

import play.modules.statsd.api.Statsd

import com.keepit.common.db.ExternalId

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.social.{BasicUserLikeEntity, BasicUser, BasicNonUser}
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsObject


class MobileMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  basicMessageCommander: MessageFetchingCommander,
  notificationCommander: NotificationCommander,
  actionAuthenticator: ActionAuthenticator,
  heimdalContextBuilder: HeimdalContextBuilderFactory,
  messageSearchCommander: MessageSearchCommander
  ) extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = JsonAction.authenticatedAsync { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt)
    }
    noticesFuture.map {notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.jsons, numUnreadUnmuted))
    }
  }

  def sendMessageAction() = JsonAction.authenticatedParseJsonAsync { request =>
    val o = request.body
    val (title, text, source) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim,
      (o \ "source").asOpt[MessageSource]
    )
    val (userExtRecipients, nonUserRecipients) = messagingCommander.recipientJsonToTypedFormat((o \ "recipients").as[Seq[JsValue]])
    val url = (o \ "url").asOpt[String]
    val urls = JsObject(o.as[JsObject].value.filterKeys(Set("url", "canonical", "og").contains).toSeq)

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "mobile")

    val messageSubmitResponse = messagingCommander.sendMessageAction(title, text, source,
        userExtRecipients, nonUserRecipients, url, urls, request.userId, contextBuilder.build) map { case (message, threadInfoOpt, messages) =>
      Ok(Json.obj(
        "id" -> message.externalId.id,
        "parentId" -> message.threadExtId.id,
        "createdAt" -> message.createdAt,
        "threadInfo" -> threadInfoOpt,
        "messages" -> messages.reverse))
    }

    messageSubmitResponse
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = JsonAction.authenticatedParseJson { request =>
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
    statsd.timing(s"messaging.replyMessage", tDiff, ALWAYS)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }


  // todo(eishay, léo): the next version of this endpoint should rename the "uri" field to "url"
  def getPagedThread(threadId: String, pageSize: Int, fromMessageId: Option[String]) = JsonAction.authenticatedAsync { request =>
    basicMessageCommander.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId)) map { case (thread, allMsgs) =>
      val url = thread.url.getOrElse("")  // needs to change when we have detached threads
      val nUrl = thread.nUrl.getOrElse("")  // needs to change when we have detached threads
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
          val adderAndAddedOpt: Option[(String,Seq[String])] = m.auxData match {
            case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => {
              Some( adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[JsValue]].map(json =>  (json \ "id").as[String]) )
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
            case Some(bnu: BasicNonUser) if bnu.kind=="email" => Json.obj("userId" -> bnu.id)
            case _ => Json.obj()
          })

          adderAndAddedOpt.map { adderAndAdded =>
            msgJson.deepMerge(Json.obj("added" -> adderAndAdded._2, "userId" -> adderAndAdded._1))
          } getOrElse{
            msgJson
          }
        })
      ))
    }
  }

  @deprecated(message = "use getPagedThread", since = "April 23, 2014")
  def getCompactThread(threadId: String) = JsonAction.authenticatedAsync { request =>
    basicMessageCommander.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId)) map { case (thread, msgs) =>
      val url = thread.url.getOrElse("")  // needs to change when we have detached threads
      val nUrl = thread.nUrl.getOrElse("")  // needs to change when we have detached threads
      val participants: Set[BasicUserLikeEntity] = msgs.map(_.participants).flatten.toSet
      Ok(Json.obj(
        "id" -> threadId,
        "uri" -> url,
        "nUrl" -> nUrl,
        "participants" -> participants,
        "messages" -> (msgs.reverse map { m =>
          val adderAndAddedOpt: Option[(String,Seq[String])] = m.auxData match {
            case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => {
              Some( adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[JsValue]].map(json =>  (json \ "id").as[String]) )
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
              case Some(bnu: BasicNonUser) if bnu.kind.name=="email" => Json.obj("userId" -> bnu.id)
              case _ => Json.obj()
          })

          adderAndAddedOpt.map { adderAndAdded =>
            msgJson.deepMerge(Json.obj("added" -> adderAndAdded._2, "userId" -> adderAndAdded._1))
          } getOrElse{
            msgJson
          }
        })
      ))
    }
  }

  def getThreadsByUrl(url: String) = JsonAction.authenticatedAsync { request =>
    messagingCommander.getThreadInfos(request.userId, url).map { case (_, threadInfos) =>
      Ok(Json.toJson(threadInfos))
    }
  }

  def hasThreadsByUrl(url: String) = JsonAction.authenticatedAsync { request =>
    messagingCommander.hasThreads(request.userId, url).map { yesorno  =>
      Ok(Json.toJson(yesorno))
    }
  }

  def searchMessages(query: String, page: Int) = JsonAction.authenticatedAsync { request =>
    messageSearchCommander.searchMessages(request.userId, query, page).map{ notifs =>
      Ok(Json.toJson(notifs.jsons))
    }
  }

  def getMessageSearchHistory() = JsonAction.authenticated { request =>
    val (queries, emails, optOut) = messageSearchCommander.getHistory(request.userId)
    Ok(Json.obj("qs" -> queries, "es" -> emails, "optOut" -> optOut))
  }

  def getMessageSearchHistoryOptOut() = JsonAction.authenticated { request =>
    Ok(Json.obj("didOptOut" -> messageSearchCommander.getHistoryOptOut(request.userId)))
  }

  def setMessageSearchHistoryOptOut() = JsonAction.authenticatedParseJson { request =>
    Ok(Json.obj("didOptOut" -> messageSearchCommander.setHistoryOptOut(request.userId, request.body.as[Boolean])))
  }

  def clearMessageSearchHistory() = JsonAction.authenticated { request =>
    messageSearchCommander.clearHistory(request.userId)
    Ok("")
  }
}
