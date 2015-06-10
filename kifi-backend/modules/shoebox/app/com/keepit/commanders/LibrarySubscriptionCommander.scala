package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ ClientResponse, DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model.{ LibrarySubscriptionRepo, UserRepo, Keep, Library, LibrarySubscription, SlackInfo, SubscriptionTrigger }
import play.api.libs.json._

import scala.concurrent.{ Future, ExecutionContext }

case class BasicSlackMessage(
  text: String,
  channel: Option[String] = None,
  username: String = "kifi-bot",
  iconUrl: String = "https://djty7jcqog9qu.cloudfront.net/assets/black/logo.png")

object BasicSlackMessage {
  implicit val writes = new Writes[BasicSlackMessage] {
    def writes(o: BasicSlackMessage): JsValue = Json.obj("text" -> o.text, "channel" -> o.channel, "username" -> o.username, "icon_url" -> o.iconUrl)
  }
}

@Singleton
class LibrarySubscriptionCommander @Inject() (
    db: Database,
    httpClient: HttpClient,
    librarySubscriptionRepo: LibrarySubscriptionRepo,
    userRepo: UserRepo,
    implicit val executionContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends Logging {

  private val httpLock = new ReactiveLock(5)

  val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(2 * 60 * 1000), maxJsonParseTime = Some(20000)))

  def sendNewKeepMessage(keep: Keep, library: Library): Seq[Future[ClientResponse]] = {
    val subscriptions: Seq[LibrarySubscription] = db.readOnlyReplica { implicit session => librarySubscriptionRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP) }

    val keeper = db.readOnlyReplica { implicit session => userRepo.get(keep.userId) }

    subscriptions.map { subscription =>
      subscription.info match {
        case info: SlackInfo =>
          val text = s"<http://www.kifi.com/${keeper.username.value}|${keeper.fullName}> just added <${keep.url}|${keep.title.getOrElse("a keep")}> to the <http://www.kifi.com/${keeper.username.value}/${library.slug.value}|${library.name}> library." // slack hypertext uses the < url | text > format
          val body = BasicSlackMessage(text)
          val response = httpLock.withLockFuture(client.postFuture(DirectUrl(info.url), Json.toJson(body)))
          log.info(s"sendNewKeepMessage: Slack message request sent to subscription.id=${subscription.id}")
          response onFailure {
            case t => log.error("sendNewKeepMessage: Slack message failed to send: " + t.getMessage); Future.failed(t)
          }
          response
        case _ =>
          Future.failed(new NoSuchFieldException("sendNewKeepMessage: SubscriptionInfo not supported"))
      }
    }
  }

  def saveSubscription(librarySubscription: LibrarySubscription): LibrarySubscription = {
    db.readWrite { implicit s => librarySubscriptionRepo.save(librarySubscription) }
  }

  def getSubsByLibraryId(id: Id[Library]): Seq[LibrarySubscription] = {
    db.readOnlyMaster { implicit s => librarySubscriptionRepo.getByLibraryId(id) }
  }

}
