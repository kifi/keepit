package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ ClientResponse, DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model.{ LibrarySubscriptionRepo, UserRepo, Keep, Library, LibrarySubscription, SlackInfo, SubscriptionTrigger }
import com.kifi.macros.json
import play.api.libs.json.{ Json, JsValue }

import scala.concurrent.{ Future, ExecutionContext }

@json case class BasicSlackMessage(
    text: String,
    destChannel: Option[String] = None,
    displayedUserName: String = "kifi",
    iconUrl: String = "https://djty7jcqog9qu.cloudfront.net/assets/black/logo.png") {
}

@Singleton
class LibrarySubscriptionCommander @Inject() (
    db: Database,
    httpClient: HttpClient,
    librarySubscriptionRepo: LibrarySubscriptionRepo,
    userRepo: UserRepo,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  val httpLock = new ReactiveLock(5)

  val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(2 * 60 * 1000), maxJsonParseTime = Some(20000)))

  def sendNewKeepMessage(keep: Keep, library: Library): Seq[Future[ClientResponse]] = {
    val subscriptions: Seq[LibrarySubscription] = db.readOnlyReplica { implicit session => librarySubscriptionRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP) }

    val keeper = db.readOnlyReplica { implicit session => userRepo.get(keep.userId) }

    subscriptions.map { subscription =>
      subscription.info match {
        case info: SlackInfo =>
          val text = s"${keeper.fullName} just added a <${keep.url}|keep> to the <http://www.kifi.com/${keeper.username}/${library.slug}|" + library.name + "> library." // slack hypertext uses the < url | text > format
          val body = BasicSlackMessage(text)
          httpLock.withLockFuture(client.postFuture(DirectUrl(info.url), Json.toJson(body)))
        case _ =>
          Future.failed(new NoSuchFieldException("[LibrarySubscriptionCommander] sendNewKeepMessage: SubscriptionInfo not supported"))
      }
    }
  }

  def saveSubscription(librarySubscription: LibrarySubscription): LibrarySubscription = {
    db.readWrite { implicit s => librarySubscriptionRepo.save(librarySubscription) }
  }

}
