package com.keepit.eliza.controllers.internal

import com.keepit.eliza._
import com.keepit.eliza.model._
import com.keepit.eliza.commanders.{MessagingCommander, MessagingIndexCommander}
import com.keepit.model._
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.social.{BasicUserLikeEntity, BasicNonUser, BasicUser}
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.ElizaServiceController

import scala.concurrent.{Promise, Await, Future}
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import org.joda.time.DateTime

import play.api.libs.json._
import play.modules.statsd.api.Statsd

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.Charset
import com.keepit.common.akka.TimeoutFuture
import java.util.concurrent.TimeoutException
import com.keepit.eliza.model.NonUserParticipant
import play.api.libs.json.JsString
import scala.Some
import com.keepit.eliza.model.NonUserThread
import play.api.libs.json.JsArray
import com.keepit.eliza.model.NonUserEmailParticipant
import play.api.libs.json.JsObject
import com.keepit.realtime.PushNotification
import com.keepit.common.KestrelCombinator
import com.keepit.heimdal.HeimdalContext
import scala.util.{Failure, Success, Try}
import play.api.libs.json.JsArray
import com.keepit.eliza.model.UserThread
import play.api.libs.json.JsObject


//For migration only
import play.api.mvc.Action
import com.keepit.common.db.slick.DBSession.RWSession

/* To future maintainers
*  If this is ever getting too slow the first things I would look at (in no particular order):
*  -External Id lookups. Make those numeric Id (instead of giant strings) and index them in the db
*  -Synchronous dependency on normalized url id in thread creation. Remove it.
*  -Go to 64 bit for the participants hash
*  -Use "Insert Ignore" where possible
*  -Lazy creation of UserThread rows
*  -Do batch updates when modifying UserThreads for a given thread (will require index on threadid)
*  -Make notifications not wait for a message ID
*/

class MessagingController @Inject() (
    threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    db: Database,
    uriNormalizationUpdater: UriNormalizationUpdater,
    messagingCommander: MessagingCommander,
    messagingIndexCommander: MessagingIndexCommander)
  extends ElizaServiceController with Logging {

  //for indexing data requests
  def getThreadContentForIndexing(sequenceNumber: Long, maxBatchSize: Long) = Action.async { request =>
    messagingIndexCommander.getThreadContentsForMessagesFromIdToId(Id[Message](sequenceNumber), Id[Message](sequenceNumber+maxBatchSize)).map{ threadContents =>
      Ok(JsArray(threadContents.map(Json.toJson(_))))
    }
  }

  def sendGlobalNotification() = Action(parse.json) { request =>
    SafeFuture {
      val data : JsObject = request.body.asInstanceOf[JsObject]

      val userIds  : Set[Id[User]]  =  (data \ "userIds").as[JsArray].value.map(v => v.asOpt[Long].map(Id[User](_))).flatten.toSet
      val title    : String         =  (data \ "title").as[String]
      val body     : String         =  (data \ "body").as[String]
      val linkText : String         =  (data \ "linkText").as[String]
      val linkUrl  : String         =  (data \ "linkUrl").as[String]
      val imageUrl : String         =  (data \ "imageUrl").as[String]
      val sticky   : Boolean        =  (data \ "sticky").as[Boolean]
      val category : NotificationCategory =  (data \ "category").as[NotificationCategory]

      messagingCommander.createGlobalNotification(userIds, title, body, linkText, linkUrl, imageUrl, sticky, category)

    }
    Status(ACCEPTED)
  }

  def verifyAllNotifications() = Action { request => //Use with caution, very expensive!
    //Will need to change when we have detached threads.
    //currently only verifies
    SafeFuture{
      log.warn("Starting notification verification!")
      val userThreads : Seq[UserThread] = db.readOnly{ implicit session => userThreadRepo.all }
      val nUrls : Map[Id[MessageThread], Option[String]] = db.readOnly{ implicit session => threadRepo.all } map { thread => (thread.id.get, thread.url) } toMap

      userThreads.foreach{ userThread =>
        if (userThread.uriId.isDefined) {
          nUrls(userThread.thread).foreach{ correctNUrl =>
            log.warn(s"Verifying notification on user thread ${userThread.id.get}")
            uriNormalizationUpdater.fixLastNotificationJson(userThread, correctNUrl)
          }
        }
      }
    }
    Status(ACCEPTED)
  }

}

