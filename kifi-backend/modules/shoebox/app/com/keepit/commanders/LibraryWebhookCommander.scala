package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, CallTimeouts, HttpClient }
import com.keepit.model._
import play.api.libs.json.{ Json, JsValue }

import scala.concurrent.{ Future, ExecutionContext }

//val KIFI_LOGO_URL = "https://djty7jcqog9qu.cloudfront.net/assets/black/logo.png"

case class BasicSlackMessage(
    text: String,
    destChannel: String = "",
    displayedUserName: String = "kifi",
    iconUrl: String = "https://djty7jcqog9qu.cloudfront.net/assets/black/logo.png") {
  def toJson: JsValue = Json.obj("text" -> text, "channel" -> destChannel, "username" -> displayedUserName, "icon_url" -> iconUrl.toString)
}

class LibraryWebhookCommander @Inject() (
    db: Database,
    httpClient: HttpClient,
    libraryWebhookRepo: LibraryWebhookRepo,
    userRepo: UserRepo,
    implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends Logging {

  def sendNewKeepWebhook(bookmark: RawBookmarkRepresentation, userId: Id[User], library: Library) = SafeFuture {
    val webhooks = db.readOnlyReplica { implicit session =>
      library.id.map { id => libraryWebhookRepo.getByLibraryIdAndTrigger(id, WebhookTrigger.NEW_KEEP) }
    }.getOrElse(throw new NoSuchFieldException) // not sure what to do in case library.id == None

    val keeperName = db.readOnlyReplica { implicit session => userRepo.get(userId).fullName }

    val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(2 * 60 * 1000), maxJsonParseTime = Some(20000))) // copied from FacebookPublishingCommander, can tweak

    webhooks.map { webhook =>
      webhook.action.\("sendThrough").toString match { // the "sendThrough" field could be "slack", "email", "eliza", etc... perhaps there's a better name
        case "slack" =>
          val text = keeperName + " just added a <$bookmark.url|keep> to the <$library.url|" + library.name + "> library." // slack hypertext uses the < url | text > format
          val body = BasicSlackMessage(text)
          client.postFuture(DirectUrl(webhook.action.\("url").toString), body.toJson)
        case _ =>
          Future.failed(new NoSuchFieldException)
      }
    }
  }
}
