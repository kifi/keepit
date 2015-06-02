package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model._
import play.api.libs.json.{ Json, JsValue }

import scala.concurrent.{ Future, ExecutionContext }

case class BasicSlackMessage(
    text: String,
    destChannel: String = "",
    displayedUserName: String = "kifi",
    iconUrl: String = "https://djty7jcqog9qu.cloudfront.net/assets/black/logo.png") {
  def toJson: JsValue = Json.obj("text" -> text, "channel" -> destChannel, "username" -> displayedUserName, "icon_url" -> iconUrl.toString)
}

class LibrarySubscriptionCommander @Inject() (
    db: Database,
    httpClient: HttpClient,
    libraryWebhookRepo: LibrarySubscriptionRepo,
    userRepo: UserRepo,
    implicit val executionContext: ExecutionContext,
    airbrake: AirbrakeNotifier) extends Logging {

  val httpLock = new ReactiveLock(1)

  def sendNewKeepMessage(bookmark: RawBookmarkRepresentation, userId: Id[User], library: Library) = Future {
    val subscriptions = db.readOnlyReplica { implicit session => libraryWebhookRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP) }

    val keeperName = db.readOnlyReplica { implicit session => userRepo.get(userId).fullName }

    val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(2 * 60 * 1000), maxJsonParseTime = Some(20000)))

    subscriptions.map { subscription =>
      subscription.info match {
        case info: SlackInfo =>
          val text = keeperName + " just added a <$bookmark.url|keep> to the <$library.url|" + library.name + "> library." // slack hypertext uses the < url | text > format
          val body = BasicSlackMessage(text)
          httpLock.withLock(client.postFuture(DirectUrl(info.url), body.toJson))
        case _ =>
          Future.failed(new NoSuchFieldException)
      }
    }
  }
}
