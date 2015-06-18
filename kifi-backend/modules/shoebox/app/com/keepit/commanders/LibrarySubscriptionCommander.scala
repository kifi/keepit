package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ ClientResponse, DirectUrl, CallTimeouts, HttpClient }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.json._

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Try, Failure, Success }

@json
case class SlackAttachment(fallback: String, text: String)

case class BasicSlackMessage( // https://api.slack.com/incoming-webhooks
  text: String,
  channel: Option[String] = None,
  username: String = "Kifi",
  iconUrl: String = "https://d1dwdv9wd966qu.cloudfront.net/img/favicon64x64.7cc6dd4.png",
  attachments: Seq[SlackAttachment] = Seq.empty)

object BasicSlackMessage {
  implicit val writes = new Writes[BasicSlackMessage] {
    def writes(o: BasicSlackMessage): JsValue = Json.obj("text" -> o.text, "channel" -> o.channel, "username" -> o.username, "icon_url" -> o.iconUrl, "attachments" -> o.attachments)
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
  val validateClient = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(1 * 1000), maxJsonParseTime = Some(1000)))

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
          val keepTitle = keep.title.exists(_.nonEmpty)
          val text = s"<http://www.kifi.com/${keeper.username.value}?kma=1|${keeper.fullName}> just added <${keep.url}|${keepTitle}> to the <http://www.kifi.com/${owner.username.value}/${library.slug.value}?kma=1|${library.name}> library."
          val attachments: Seq[SlackAttachment] = keep.note.map { note => Seq(SlackAttachment(fallback = "", text = "\"" + note + "\" - " + keeper.firstName)) }.getOrElse(Seq.empty)
          val body = BasicSlackMessage(text = text, attachments = attachments)
          val response = httpLock.withLockFuture(client.postFuture(DirectUrl(info.url), Json.toJson(body)))
          log.info(s"sendNewKeepMessage: Slack message request sent to subscription.id=${subscription.id}")
          response.onComplete {
            case Success(res) if res.status == 400 =>
              log.error(s"sendNewKeepMessage: invalid webhook on subscriptionId=${subscription.id.get}, disabling")
              db.readWrite { implicit s => librarySubscriptionRepo.save(subscription.withState(LibrarySubscriptionStates.DISABLED)) }
            case Failure(t) =>
              log.error("sendNewKeepMessage: Future failed: " + t.getMessage);
            case _ => log.info(s"sendNewKeepMessage: Slack message to subscriptionId=${subscription.id.get} succeeded.")
          }
          response
        case _ =>
          Future.failed(new NoSuchFieldException("sendNewKeepMessage: SubscriptionInfo not supported"))
      }
    }
  }

  def saveSubByLibIdAndKey(libId: Id[Library], subKey: LibrarySubscriptionKey, state: State[LibrarySubscription] = LibrarySubscriptionStates.ACTIVE)(implicit session: RWSession): LibrarySubscription = {
    librarySubscriptionRepo.save(LibrarySubscription(libraryId = libId, name = subKey.name, trigger = SubscriptionTrigger.NEW_KEEP, info = subKey.info)) // TODO: extend this to other triggers
  }

  def saveSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Seq[LibrarySubscription] = {
    subKeys.map { key => saveSubByLibIdAndKey(libId, key) }
  }

  def updateSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Boolean = {

    def hasSameNameOrEndpoint(key: LibrarySubscriptionKey, sub: LibrarySubscription) = sub.name == key.name || sub.info.hasSameEndpoint(key.info)
    def saveUpdates(currSubs: => Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {
      subKeys.foreach { key =>
        isValidWebhook(key.info) foreach { isValid =>
          val state = { if (isValid) { LibrarySubscriptionStates.ACTIVE } else { LibrarySubscriptionStates.DISABLED } }
          log.info(s"Subscription saved with state=${state}")
          currSubs.find {
            hasSameNameOrEndpoint(key, _)
          } match {
            case None => saveSubByLibIdAndKey(libId, key, state = state) // key represents a new sub, save it
            case Some(equivalentSub) => librarySubscriptionRepo.save(equivalentSub.copy(name = key.name, info = key.info, state = state)) // key represents an old sub, update it
          }
        }
      }
    }
    // TODO: refactor these \/two^ into one function
    def removeDifferences(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {
      currSubs.foreach { currSub =>
        subKeys.find {
          hasSameNameOrEndpoint(_, currSub)
        } match {
          case None => librarySubscriptionRepo.save(currSub.copy(state = LibrarySubscriptionStates.INACTIVE))
          case Some(key) => // currSub has already been updated above, do nothing
        }
      }
    }

    val currentSubs = librarySubscriptionRepo.getByLibraryId(libId, excludeStates = Set.empty)

    if (currentSubs.isEmpty) {
      saveSubsByLibIdAndKey(libId, subKeys)
    } else {
      saveUpdates(currentSubs, subKeys) // save new subs and changes to existing subs
      removeDifferences(currentSubs, subKeys) // inactivate existing subs that are not in subKeys
    }

    val newSubs = librarySubscriptionRepo.getByLibraryId(libId)

    currentSubs != newSubs
  }

  def isValidWebhook(subInfo: SubscriptionInfo): Future[Boolean] = {
    subInfo match {
      case s: SlackInfo => httpClient.getFuture(DirectUrl(subInfo.asInstanceOf[SlackInfo].url)).map { _.status == 500 }
      case _ => Future.successful(false)
    }
  }

}
