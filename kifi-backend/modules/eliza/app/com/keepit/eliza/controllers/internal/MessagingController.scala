package com.keepit.eliza.controllers.internal

import com.keepit.eliza._
import com.keepit.eliza.model._
import com.keepit.eliza.commanders.{ NotificationDeliveryCommander, MessagingCommander }
import com.keepit.model._
import com.keepit.common.db.{ Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.ElizaServiceController

import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import play.api.libs.json._
import com.keepit.common.json.TupleFormat._

//For migration only
import play.api.mvc.Action

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
  messagingIndexCommander: MessagingIndexCommander,
  notificationCommander: NotificationDeliveryCommander)
    extends ElizaServiceController with Logging {

  //for indexing data requests
  def getThreadContentForIndexing(sequenceNumber: Long, maxBatchSize: Long) = Action.async { request =>
    (new SafeFuture(messagingIndexCommander.getThreadContentsForMessagesFromIdToId(Id[ElizaMessage](sequenceNumber), Id[ElizaMessage](sequenceNumber + maxBatchSize)))).map { threadContents =>
      Ok(JsArray(threadContents.map(Json.toJson(_))))
    }
  }

  def getNonUserThreadMuteInfo(token: String) = Action { request =>
    val result = messagingCommander.getNonUserThreadOptByAccessToken(ThreadAccessToken(token)).map { nonUserThread =>
      (nonUserThread.participant.identifier, nonUserThread.muted)
    }
    Ok(Json.toJson(result))
  }

  def setNonUserThreadMuteState(token: String, muted: Boolean) = Action {
    // returns whether the mute state was modified
    val result = messagingCommander.getNonUserThreadOptByAccessToken(ThreadAccessToken(token)) match {
      case Some(nut) => messagingCommander.setNonUserThreadMuteState(nut.id.get, muted)
      case _ => false
    }
    Ok(Json.toJson(result))
  }

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    Ok(Json.toJson(messagingCommander.keepAttribution(userId, uriId)))
  }

  def checkUrisDiscussed(userId: Id[User]) = Action.async(parse.json) { request =>
    val uriIds = request.body.as[Seq[Id[NormalizedURI]]]
    messagingCommander.checkUrisDiscussed(userId, uriIds).map { res =>
      Ok(Json.toJson(res))
    }
  }

  def getUnreadNotifications(userId: Id[User], howMany: Int) = Action {
    val userThreadViews = notificationCommander.getUnreadUnmutedThreads(userId, howMany)
    Ok(Json.toJson(userThreadViews))
  }

}

