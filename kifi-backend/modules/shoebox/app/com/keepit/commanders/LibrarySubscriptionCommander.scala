package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ ClientResponse, DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model._
import play.api.libs.json._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }

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

    val (subscriptions, keeper, owner) = db.readOnlyReplica { implicit session =>
      val subscriptions = librarySubscriptionRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP)
      val keeper = userRepo.get(keep.userId)
      val owner = userRepo.get(library.ownerId)
      (subscriptions, keeper, owner)
    }

    subscriptions.map { subscription =>
      subscription.info match {
        case info: SlackInfo =>
          val text = s"<http://www.kifi.com/${keeper.username.value}|${keeper.fullName}> just added <${keep.url}|${keep.title.getOrElse("a keep")}> to the <http://www.kifi.com/${owner.username.value}/${library.slug.value}|${library.name}> library." // slack hypertext uses the < url | text > format
          val body = BasicSlackMessage(text)
          val response = httpLock.withLockFuture(client.postFuture(DirectUrl(info.url), Json.toJson(body)))
          log.info(s"sendNewKeepMessage: Slack message request sent to subscription.id=${subscription.id}")
          response.onComplete {
            case Success(res) if res.status != 200 =>
              log.error("sendNewKeepMessage: Slack message failed to send: status=" + res.status);
            case Failure(t) =>
              log.error("sendNewKeepMessage: Future failed: " + t.getMessage);
            case _ => log.info("sendNewKeepMessage: Slack message succeeded.")
          }
          response
        case _ =>
          Future.failed(new NoSuchFieldException("sendNewKeepMessage: SubscriptionInfo not supported"))
      }
    }
  }

  def saveSubByLibIdAndKey(libId: Id[Library], subKey: LibrarySubscriptionKey)(implicit session: RWSession): LibrarySubscription = {
    librarySubscriptionRepo.save(LibrarySubscription(libraryId = libId, name = subKey.name, trigger = SubscriptionTrigger.NEW_KEEP, info = subKey.info)) // TODO: extend this to other triggers
  }

  def saveSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Seq[LibrarySubscription] = {
    subKeys.map { key => saveSubByLibIdAndKey(libId, key) }
  }

  def updateSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Boolean = {

    def hasSameNameOrEndpoint(key: LibrarySubscriptionKey, sub: LibrarySubscription) = sub.name == key.name || sub.info.hasSameEndpoint(key.info)
    def saveUpdates(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {
      subKeys.foreach { key =>
        currSubs.find {
          hasSameNameOrEndpoint(key, _)
        } match {
          case None => saveSubByLibIdAndKey(libId, key) // key represents a new sub, save it
          case Some(equivalentSub) => librarySubscriptionRepo.save(equivalentSub.copy(name = key.name, info = key.info)) // key represents an old sub, update it
        }
      }
    }
    // TODO: refactor these \/two^ into one function
    def removeDifferences(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {
      currSubs.foreach { currSub =>
        subKeys.find {
          hasSameNameOrEndpoint(_, currSub)
        } match {
          case None => librarySubscriptionRepo.save(currSub.copy(state = LibrarySubscriptionStates.INACTIVE)) // currSub not found in subKeys, inactivate it
          case Some(key) => // currSub has already been updated above, do nothing
        }
      }
    }

    val currSubs = librarySubscriptionRepo.getByLibraryId(libId)

    println(currSubs)

    if (currSubs.isEmpty) {
      saveSubsByLibIdAndKey(libId, subKeys)
    } else {
      saveUpdates(currSubs, subKeys) // save new subs and changes to existing subs
      removeDifferences(currSubs, subKeys) // inactivate existing subs that are not in subKeys
    }

    val newSubs = librarySubscriptionRepo.getByLibraryId(libId)

    println(newSubs)

    currSubs != newSubs
  }

}
