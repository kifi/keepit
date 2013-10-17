package com.keepit.common.analytics

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.google.inject.{ Inject, Singleton, Provider }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.search.{SearchConfigExperiment, ArticleSearchResultStore, ArticleSearchResult, SearchServiceClient}
import com.keepit.shoebox.BrowsingHistoryTracker
import com.keepit.shoebox.ClickHistoryTracker
import play.api.libs.json.{JsArray, JsObject}
import scala.util.Success
import com.keepit.normalizer.{NormalizationCandidate}
import com.keepit.common.net.{Host, URI}
import com.keepit.heimdal.{UserEventType, UserEvent, HeimdalServiceClient, UserEventContextBuilder}

abstract class EventListener(userRepo: UserRepo, normalizedURIRepo: NormalizedURIRepo) extends Logging {
  def onEvent: PartialFunction[Event, Unit]
}

@Singleton
class EventHelper @Inject() (
  actor: ActorInstance[EventHelperActor],
  listeners: Set[EventListener]) {
  def newEvent(event: Event): Unit = actor.ref ! event

  def matchEvent(event: Event): Seq[String] =
    listeners.filter(_.onEvent.isDefinedAt(event)).map(_.getClass.getSimpleName.replaceAll("\\$", "")).toSeq
}

class EventHelperActor @Inject() (
  airbrake: AirbrakeNotifier,
  listeners: Set[EventListener],
  eventStream: EventStream)
  extends FortyTwoActor(airbrake) {

  def receive = {
    case event: Event =>
      eventStream.streamEvent(event)
      val events = listeners.filter(_.onEvent.isDefinedAt(event))
      events.map(_.onEvent(event))
    case m => throw new UnsupportedActorMessage(m)
  }
}

@Singleton
class ResultClickedListener @Inject() (
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  searchServiceClient: SearchServiceClient,
  db: Database,
  bookmarkRepo: BookmarkRepo,
  userBookmarkClicksRepo: UserBookmarkClicksRepo,
  clickHistoryTracker: ClickHistoryTracker,
  articleSearchResultStore: ArticleSearchResultStore,
  heimdal: HeimdalServiceClient) extends EventListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, eventName, externalUser, _, experiments, metaData, _), createdAt, _) if ResultSource.isDefinedAt(eventName) =>
      val (user, meta, bookmark) = db.readOnly { implicit s =>
        val (user, meta) = searchParser(externalUser, metaData, eventName)
        val bookmark = meta.normUrl.map(n => bookmarkRepo.getByUriAndUser(n.id.get, user.id.get)).flatten
        (user, meta, bookmark)
      }
      val obfuscatedSearchSession = meta.queryUUID.map(articleSearchResultStore.getSearchSession).map(ArticleSearchResult.obfuscate(_, user.id.get))

      val contextBuilder = new UserEventContextBuilder()
      contextBuilder += ("searchSession", obfuscatedSearchSession.getOrElse(""))
      contextBuilder += ("resultClicked", ResultSource(eventName))
      contextBuilder += ("resultPosition", meta.rank)
      contextBuilder += ("kifiResultsCount", meta.kifiResultsCount)
      meta.searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
      heimdal.trackEvent(UserEvent(user.id.get.id, contextBuilder.build, UserEventType("search_result_clicked"), createdAt))

      meta.normUrl.filter { n =>
        // exclude an uri not kept by any user. uri is not kept if either active or inactive.
        (n.state != NormalizedURIStates.ACTIVE && n.state != NormalizedURIStates.INACTIVE)
      }.foreach { n =>
        searchServiceClient.logResultClicked(user.id.get, meta.query, n.id.get, meta.rank, !bookmark.isEmpty)
        clickHistoryTracker.add(user.id.get, n.id.get)

        // if bookmark is kept by this user
        if (bookmark.isDefined && user.id.isDefined){
          db.readWrite{ implicit s =>
            userBookmarkClicksRepo.increaseCounts(bookmark.get.userId, bookmark.get.uriId, isSelf = true)
          }
        }
        // if kept by others, others get credit
        if (!bookmark.isDefined){
          searchServiceClient.sharingUserInfo(user.id.get, n.id.get).onComplete{
            case Success(sharingUserInfo) => {
              sharingUserInfo.sharingUserIds.foreach{ userId =>
                db.readWrite{ implicit s =>
                  userBookmarkClicksRepo.increaseCounts(userId, n.id.get, isSelf = false)
                }
              }
            }
            case _ => log.warn("fail to get sharing user info from search client")
          }
        }
      }
  }

  object ResultSource extends PartialFunction[String, String] {
    val kifiResultClicked = "kifiResultClicked"
    val googleResultClicked = "googleResultClicked"
    val validEvents = Set(kifiResultClicked, googleResultClicked)

    def isDefinedAt(eventName: String): Boolean = validEvents.contains(eventName)
    def apply(eventName: String): String = eventName match {
      case this.kifiResultClicked => "KiFi"
      case this.googleResultClicked => "Google"
    }
  }

  case class SearchMeta(
    query: String,
    url: String,
    normUrl: Option[NormalizedURI],
    rank: Int,
    kifiResultsCount: Int,
    queryUUID: Option[ExternalId[ArticleSearchResult]],
    searchExperiment: Option[Id[SearchConfigExperiment]]
  )

  def searchParser(externalUser: ExternalId[User], json: JsObject, eventName: String)(implicit s: RSession) = {
    val query = (json \ "query").asOpt[String].getOrElse("")
    val url: String = getDestinationURL(json, eventName).getOrElse("")
    val user = userRepo.get(externalUser)
    val normUrl = normalizedURIRepo.getByUri(url)
    val rank = getRank(json, eventName)
    val kifiResultsCount = (json \ "kifiResultsCount").as[Int]
    val queryUUID = ExternalId.asOpt[ArticleSearchResult]((json \ "queryUUID").asOpt[String].getOrElse(""))
    val searchExperiment = (json \ "experimentId").asOpt[Long].map(Id[SearchConfigExperiment](_))
    (user, SearchMeta(query, url, normUrl, rank, kifiResultsCount, queryUUID, searchExperiment))
  }

  private def getRank(json: JsObject, eventName: String): Int = {
    eventName match {
      case ResultSource.kifiResultClicked => (json \ "whichResult").asOpt[Int].getOrElse(0)
      case _ => 0
    }
  }

  private def getDestinationURL(json: JsObject, eventName: String): Option[String] = {
    val urlOpt = (json \ "url").asOpt[String]
    eventName match {
      case ResultSource.googleResultClicked => urlOpt.flatMap { url => getDestinationFromGoogleURL(url) }
      case _ => urlOpt
    }
  }

  private def getDestinationFromGoogleURL(url: String): Option[String] = {
    val urlOpt = url match {
      case URI(_, _, Some(Host("com", "youtube", _*)), _, _, _, _) => Some(url)
      case URI(_, _, Some(Host("org", "wikipedia", _*)), _, _, _, _) => Some(url)
      case URI(_, _, Some(host), _, Some("/url"), Some(query), _) if host.domain.contains("google") =>
        query.params.find(_.name == "url").flatMap { _.decodedValue }
      case _ =>
        None
    }
    if (!urlOpt.isDefined) log.error(s"failed to extract the destination URL from: ${url}")
    urlOpt
  }
}

@Singleton
class UsefulPageListener @Inject() (
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  db: Database,
  browsingHistoryTracker: BrowsingHistoryTracker)
  extends EventListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "usefulPage", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, url, normUrl) = db.readOnly { implicit s =>
        val user = userRepo.get(externalUser)
        val url = (metaData \ "url").asOpt[String].getOrElse("")
        val normUrl = normalizedURIRepo.getByUri(url)
        (user, url, normUrl)
      }
      normUrl.foreach(n => browsingHistoryTracker.add(user.id.get, n.id.get))
  }
}

@Singleton
class SliderShownListener @Inject() (
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  db: Database,
  sliderHistoryTracker: SliderHistoryTracker)
  extends EventListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "sliderShown", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, normUri) = db.readWrite(attempts = 3) { implicit s =>
        val user = userRepo.get(externalUser)
        val normUri = (metaData \ "url").asOpt[String].map { url =>
          normalizedURIRepo.internByUri(url, NormalizationCandidate(metaData): _*)
        }
        (user, normUri)
      }
      normUri.foreach(n => sliderHistoryTracker.add(user.id.get, n.id.get))
  }
}

abstract class SearchUnloadListener(userRepo: UserRepo, normalizedURIRepo: NormalizedURIRepo)
  extends EventListener(userRepo, normalizedURIRepo)

@Singleton
class SearchUnloadListenerImpl @Inject() (
  db: Database,
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  persistEventProvider: Provider[EventPersister],
  store: MongoEventStore,
  searchClient: SearchServiceClient,
  articleSearchResultStore: ArticleSearchResultStore,
  heimdal: HeimdalServiceClient,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
  extends SearchUnloadListener(userRepo, normalizedURIRepo) with Logging {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, "searchUnload", extUserId, _, _, metaData, _), createdAt, _) => {
      val userId = db.readOnly { implicit session => userRepo.get(extUserId).id.get }
      val queryUUID = ExternalId.asOpt[ArticleSearchResult]((metaData \ "queryUUID").asOpt[String].getOrElse(""))
      val kifiResultsCount = (metaData \ "kifiShownURIs").as[JsArray].value.length
      val kifiResultsClicked = (metaData \ "kifiResultsClicked").as[Int]
      val googleResultsClicked = (metaData \ "googleResultsClicked").as[Int]
      val searchExperiment = (metaData \ "experimentId").asOpt[Long].map(Id[SearchConfigExperiment](_))
      val obfuscatedSearchSession = queryUUID.map(articleSearchResultStore.getSearchSession).map(ArticleSearchResult.obfuscate(_, userId))

      val contextBuilder = new UserEventContextBuilder()
      contextBuilder += ("searchSession", obfuscatedSearchSession.getOrElse(""))
      searchExperiment.foreach { id => contextBuilder += ("searchExperiment", id.id) }
      contextBuilder += ("kifiResultsCount", kifiResultsCount)
      contextBuilder += ("kifiResultsClicked", kifiResultsClicked)
      contextBuilder += ("googleResultsClicked", googleResultsClicked)
      heimdal.trackEvent(UserEvent(userId.id, contextBuilder.build, UserEventType("search_ended"), createdAt))
    }
  }
}

class FakeSearchUnloadListenerImpl @Inject() (
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo)
  extends SearchUnloadListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case event @ Event(_, UserEventMetadata(EventFamilies.SEARCH, "searchUnload", _, _, _, metaData, _), _, _) =>
  }
}
