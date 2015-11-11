package com.keepit.commanders

import java.net.URLEncoder

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, ClientResponse, DirectUrl, HttpClient, NonOKResponseException }
import com.keepit.common.path.Path
import com.keepit.model._
import com.keepit.slack.models.{ SlackAttachment, SlackMessage }
import play.api.http.Status._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@ImplementedBy(classOf[LibrarySubscriptionCommanderImpl])
trait LibrarySubscriptionCommander {
  def sendNewKeepMessage(keep: Keep, library: Library): Seq[Future[ClientResponse]]
  def updateSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit
}

@Singleton
class LibrarySubscriptionCommanderImpl @Inject() (
    db: Database,
    httpClient: HttpClient,
    librarySubscriptionRepo: LibrarySubscriptionRepo,
    userRepo: UserRepo,
    organizationRepo: OrganizationRepo,
    implicit val executionContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    protected val airbrake: AirbrakeNotifier) extends LibrarySubscriptionCommander with Logging {

  private val httpLock = new ReactiveLock(5)

  val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(2 * 60 * 1000), maxJsonParseTime = Some(20000)))

  def slackMessageForNewKeep(user: User, keep: Keep, library: Library, channel: String): SlackMessage = {
    val keepTitle = if (keep.title.exists(_.nonEmpty)) { keep.title.get } else { "a keep" }
    val userRedir = URLEncoder.encode(Json.obj("t" -> "us", "uid" -> user.externalId).toString(), "ascii")
    val libRedir = URLEncoder.encode(Json.obj("t" -> "lv", "lid" -> Library.publicId(library.id.get).id).toString(), "ascii")
    val userLink = Path(s"redir?data=$userRedir&kma=1").absolute
    val libLink = Path(s"redir?data=$libRedir&kma=1").absolute
    val text = s"<$userLink|${user.fullName}> just added <${keep.url}|$keepTitle> to the <$libLink|${library.name}> library."
    val attachments: Seq[SlackAttachment] = keep.note.toSeq.collect { case content if content.nonEmpty => SlackAttachment(fallback = "Check out this keep", text = s"${content} - ${user.firstName}") }
    SlackMessage(text = text, channel = Some(channel), attachments = attachments)
  }

  def sendNewKeepMessage(keep: Keep, library: Library): Seq[Future[ClientResponse]] = {

    val (subscriptions, keeper) = db.readOnlyReplica { implicit session =>
      val subscriptions = librarySubscriptionRepo.getByLibraryIdAndTrigger(library.id.get, SubscriptionTrigger.NEW_KEEP, excludeStates = Set(LibrarySubscriptionStates.INACTIVE, LibrarySubscriptionStates.DISABLED))
      val keeper = userRepo.get(keep.userId)
      (subscriptions, keeper)
    }

    subscriptions.map { subscription =>
      subscription.info match {
        case info: SlackInfo =>
          val message = slackMessageForNewKeep(keeper, keep, library, subscription.name.toLowerCase)

          val response = httpLock.withLockFuture(client.postFuture(DirectUrl(info.url), Json.toJson(message)))

          response.onComplete {
            case Success(res) =>
            case Failure(t: NonOKResponseException) =>
              t.response.status match {
                case NOT_FOUND => // returned when the webhook is invalid
                  db.readWrite { implicit s => librarySubscriptionRepo.save(subscription.withState(LibrarySubscriptionStates.DISABLED)) }
                case INTERNAL_SERVER_ERROR if t.response.body == "Invalid channel specified" =>
                  db.readWrite { implicit s => librarySubscriptionRepo.save(subscription.withState(LibrarySubscriptionStates.DISABLED)) }
                case TOO_MANY_REQUEST =>
                  airbrake.notify(s"[sendNewKeepMessage] can't send to library subscription ${subscription.id.get}", t)
                case _ =>
                  airbrake.notify(s"s[sendNewKeepMessage] can't send message to slack", t)
              }

            case _ =>
              airbrake.notify("s[sendNewKeepMessage] can't send message to slack for unknown reason")
          }
          response
        case _ =>
          Future.failed(new Exception("sendNewKeepMessage: SubscriptionInfo not supported"))
      }
    }
  }

  def saveSubByLibIdAndKey(libId: Id[Library], subKey: LibrarySubscriptionKey, state: State[LibrarySubscription] = LibrarySubscriptionStates.ACTIVE)(implicit session: RWSession): LibrarySubscription = {
    librarySubscriptionRepo.save(LibrarySubscription(libraryId = libId, name = subKey.name, trigger = SubscriptionTrigger.NEW_KEEP, info = subKey.info)) // TODO: extend this to other triggers
  }

  def saveSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Seq[LibrarySubscription] = {
    subKeys.map { key => saveSubByLibIdAndKey(libId, key) }
  }

  def updateSubsByLibIdAndKey(libId: Id[Library], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {

    def hasSameNameAndEndpoint(key: LibrarySubscriptionKey, sub: LibrarySubscription) = sub.name == key.name && sub.info.hasSameEndpoint(key.info)
    def saveUpdates(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {
      subKeys.foreach { key =>
        currSubs.find {
          hasSameNameAndEndpoint(key, _)
        } match {
          case None => saveSubByLibIdAndKey(libId, key) // key represents a new sub, save it
          case Some(equivalentSub) if equivalentSub.state == LibrarySubscriptionStates.INACTIVE => librarySubscriptionRepo.save(equivalentSub.copy(name = key.name, info = key.info, state = LibrarySubscriptionStates.ACTIVE))
          case _ =>
        }
      }
    }
    // TODO: refactor these \/two^ into one function
    def removeDifferences(currSubs: Seq[LibrarySubscription], subKeys: Seq[LibrarySubscriptionKey])(implicit session: RWSession): Unit = {
      currSubs.foreach { currSub =>
        subKeys.find {
          hasSameNameAndEndpoint(_, currSub)
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
  }

}
