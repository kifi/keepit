package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ ClientResponse, DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model._
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

    val keeper = db.readOnlyMaster { implicit session => userRepo.get(keep.userId) }

    val owner = db.readOnlyMaster { implicit session => userRepo.get(library.ownerId) }

    subscriptions.map { subscription =>
      subscription.info match {
        case info: SlackInfo =>
          val text = s"<http://www.kifi.com/${keeper.username.value}|${keeper.fullName}> just added <${keep.url}|${keep.title.getOrElse("a keep")}> to the <http://www.kifi.com/${owner.username.value}/${library.slug.value}|${library.name}> library." // slack hypertext uses the < url | text > format
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

  def saveSubByLibIdAndKey(libId: Id[Library], subKey: LibrarySubscriptionKey): LibrarySubscription = {
    saveSubscription(LibrarySubscription(libraryId = libId, name = subKey.name, trigger = SubscriptionTrigger.NEW_KEEP, info = subKey.info))
  }

  def saveSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey]): Seq[LibrarySubscription] = {
    db.readWrite { implicit s =>
      subKeys.map { key => saveSubByLibIdAndKey(libId, key) }
    }
  }

  def updateSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey]): Boolean = {

    def hasSameNameOrEndpoint(key: LibrarySubscriptionKey, sub: LibrarySubscription) = sub.name == key.name || sub.info.hasSameEndpoint(key.info)
    def saveUpdates(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey]) {
      subKeys.foreach { key =>
        currSubs.find {
          hasSameNameOrEndpoint(key, _)
        } match {
          case None => saveSubByLibIdAndKey(libId, key) // key represents a new sub, save it
          case Some(equivalentSub) => saveSubscription(equivalentSub.copy(name = key.name, info = key.info)) // key represents an old sub, update it
        }
      }
    }
    // TODO: refactor these \/ two ^ into one function
    def removeDifferences(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey]) {
      currSubs.foreach { currSub =>
        subKeys.find {
          hasSameNameOrEndpoint(_, currSub)
        } match {
          case None => saveSubscription(currSub.copy(state = LibrarySubscriptionStates.INACTIVE)) // currSub not found in subKeys, inactivate it
          case Some(key) => // currSub has already been updated above, do nothing
        }
      }
    }

    val currSubs = getSubsByLibraryId(libId)
    val newSubs = db.readWrite { implicit s =>
      if (currSubs.isEmpty) {
        saveSubsByLibIdAndKey(libId, subKeys)
      } else {
        saveUpdates(currSubs, subKeys) // save new subs and changes to existing subs
        removeDifferences(currSubs, subKeys) // remove existing subs that are not in subKeys
      }
      getSubsByLibraryId(libId)
    }

    currSubs != newSubs

  }

  def getSubsByLibraryId(id: Id[Library]): Seq[LibrarySubscription] = {
    db.readOnlyMaster { implicit s => librarySubscriptionRepo.getByLibraryId(id) }
  }

}
