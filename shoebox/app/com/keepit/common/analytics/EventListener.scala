package com.keepit.common.analytics

import play.api.libs.json.{ JsArray, JsBoolean, JsNumber, JsObject, JsString }
import com.keepit.serializer.EventSerializer
import scala.collection.JavaConversions._
import java.util.{ Set => JSet }
import com.google.inject.{ Inject, Singleton }
import com.keepit.inject._
import com.keepit.model._
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.net.Host
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.actor.ActorFactory
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.search.{ SearchServiceClient, ArticleSearchResultRef, BrowsingHistoryTracker, ClickHistoryTracker }
import com.keepit.common.akka.FortyTwoActor
import akka.actor.ActorSystem
import akka.actor.Props
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import com.google.inject.Provider
import com.keepit.serializer.SearchResultInfoSerializer
import com.keepit.search.TrainingDataLabeler
import com.keepit.search.SearchStatisticsExtractorFactory
import com.keepit.search.UriLabel
import com.keepit.serializer.SearchStatisticsSerializer

abstract class EventListenerPlugin(
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo)
  extends SchedulingPlugin with Logging {

  def onEvent: PartialFunction[Event, Unit]

  case class SearchMeta(
    query: String,
    url: String,
    normUrl: Option[NormalizedURI],
    rank: Int,
    queryUUID: Option[ExternalId[ArticleSearchResultRef]])

  def searchParser(externalUser: ExternalId[User], json: JsObject, eventName: String)(implicit s: RSession) = {
    val query = (json \ "query").asOpt[String].getOrElse("")
    val url: String = getDestinationURL(json, eventName).getOrElse("")
    val user = userRepo.get(externalUser)
    val normUrl = normalizedURIRepo.getByNormalizedUrl(url)
    val rank = getRank(json, eventName)
    val queryUUID = ExternalId.asOpt[ArticleSearchResultRef]((json \ "queryUUID").asOpt[String].getOrElse(""))
    (user, SearchMeta(query, url, normUrl, rank, queryUUID))
  }

  private def getRank(json: JsObject, eventName: String): Int = {
    eventName match {
      case SearchEventName.kifiResultClicked => (json \ "whichResult").asOpt[Int].getOrElse(0)
      case _ => 0
    }
  }

  private def getDestinationURL(json: JsObject, eventName: String): Option[String] = {
    val urlOpt = (json \ "url").asOpt[String]
    eventName match {
      case SearchEventName.googleResultClicked => urlOpt.flatMap { url => getDestinationFromGoogleURL(url) }
      case _ => urlOpt
    }
  }

  private def getDestinationFromGoogleURL(url: String): Option[String] = {
    val urlOpt = url match {
      case URI(_, _, Some(Host("com", "google", _*)), _, Some("/url"), Some(query), _) =>
        query.params.find(_.name == "url").flatMap { _.decodedValue }
      case _ =>
        None
    }
    if (!urlOpt.isDefined) log.error(s"failed to extract the destination URL from: ${url}")
    urlOpt
  }
}

object SearchEventName {
  val kifiResultClicked = "kifiResultClicked"
  val googleResultClicked = "googleResultClicked"

  val validEventNames = Set(kifiResultClicked, googleResultClicked)
}

@Singleton
class EventHelper @Inject() (
  actorFactory: ActorFactory[EventHelperActor],
  listeners: JSet[EventListenerPlugin]) {
  private lazy val actor = actorFactory.get()

  def newEvent(event: Event): Unit = actor ! event

  def matchEvent(event: Event): Seq[String] =
    listeners.filter(_.onEvent.isDefinedAt(event)).map(_.getClass.getSimpleName.replaceAll("\\$", "")).toSeq
}

class EventHelperActor @Inject() (
  healthcheckPlugin: HealthcheckPlugin,
  listeners: JSet[EventListenerPlugin],
  eventStream: EventStream)
  extends FortyTwoActor(healthcheckPlugin) {

  def receive = {
    case event: Event =>
      eventStream.streamEvent(event)
      val events = listeners.filter(_.onEvent.isDefinedAt(event))
      events.map(_.onEvent(event))
  }
}

@Singleton
class ResultClickedListener @Inject() (
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  searchServiceClient: SearchServiceClient,
  clickHistoryTracker: ClickHistoryTracker,
  db: Database,
  bookmarkRepo: BookmarkRepo)
  extends EventListenerPlugin(userRepo, normalizedURIRepo) {

  import SearchEventName._

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, eventName, externalUser, _, experiments, metaData, _), _, _) if validEventNames.contains(eventName) =>
      val (user, meta, bookmark) = db.readOnly { implicit s =>
        val (user, meta) = searchParser(externalUser, metaData, eventName)
        val bookmark = meta.normUrl.map(n => bookmarkRepo.getByUriAndUser(n.id.get, user.id.get)).flatten
        (user, meta, bookmark)
      }
      meta.normUrl.filter { n =>
        // exclude an uri not kept by any user. uri is not kept if either active or inactive.
        (n.state != NormalizedURIStates.ACTIVE && n.state != NormalizedURIStates.INACTIVE)
      }.foreach { n =>
        searchServiceClient.logResultClicked(user.id.get, meta.query, n.id.get, meta.rank, !bookmark.isEmpty)
        clickHistoryTracker.add(user.id.get, n.id.get)
      }
  }
}

@Singleton
class UsefulPageListener @Inject() (
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  db: Database,
  browsingHistoryTracker: BrowsingHistoryTracker)
  extends EventListenerPlugin(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "usefulPage", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, url, normUrl) = db.readOnly { implicit s =>
        val user = userRepo.get(externalUser)
        val url = (metaData \ "url").asOpt[String].getOrElse("")
        val normUrl = normalizedURIRepo.getByNormalizedUrl(url)
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
  extends EventListenerPlugin(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "sliderShown", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, normUri) = db.readWrite(attempts = 2) { implicit s =>
        val user = userRepo.get(externalUser)
        val normUri = (metaData \ "url").asOpt[String].map { url =>
          normalizedURIRepo.getByNormalizedUrl(url).getOrElse(
            normalizedURIRepo.save(NormalizedURIFactory(url, NormalizedURIStates.ACTIVE)))
        }
        (user, normUri)
      }
      normUri.foreach(n => sliderHistoryTracker.add(user.id.get, n.id.get))
  }
}

abstract class SearchUnloadListener(userRepo: UserRepo, normalizedURIRepo: NormalizedURIRepo) extends EventListenerPlugin(userRepo, normalizedURIRepo)

@Singleton
class SearchUnloadListenerImpl @Inject() (
  db: Database,
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  persistEventProvider: Provider[PersistEventPlugin],
  store: MongoEventStore,
  searchClient: SearchServiceClient,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
  extends SearchUnloadListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {

    case Event(_, UserEventMetadata(EventFamilies.SEARCH, "searchUnload", extUserId, _, _, metaData, _), _, _) => {

      val kifiClicks = (metaData \ "kifiResultsClicked").asOpt[Int].getOrElse(-1)
      val googleClicks = (metaData \ "googleResultsClicked").asOpt[Int].getOrElse(-1)

      if (kifiClicks > 0 || googleClicks > 0) {

        val googleUris = (metaData \ "googleClickedURIs").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        val kifiClickedUris = (metaData \ "kifiClickedURIs").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        val kifiShownUris = (metaData \ "kifiShownURIs").asOpt[Seq[String]].getOrElse(Seq.empty[String])
        val uuid = (metaData \ "queryUUID").asOpt[String].get
        val queryString = (metaData \ "query").asOpt[String].get

        val (userId, kifiClickedIds, kifiShownIds, googleClickedIds) = db.readOnly { implicit s =>
          val userId = userRepo.get(extUserId).id.get
          val kifiClickedIds = kifiClickedUris.flatMap{ normalizedURIRepo.getByNormalizedUrl(_) }.flatMap(_.id)
          val googleClickedIds = googleUris.flatMap{ normalizedURIRepo.getByNormalizedUrl(_) }.flatMap(_.id)
          val kifiShownIds = kifiShownUris.flatMap{ normalizedURIRepo.getByNormalizedUrl(_) }.flatMap(_.id)
          (userId, kifiClickedIds, googleClickedIds, kifiShownIds)
        }

        searchClient.persistSearchStatistics(uuid, queryString, userId, kifiClickedIds, googleClickedIds, kifiShownIds)
      }

    }
  }
}

class FakeSearchUnloadListenerImpl @Inject() (userRepo: UserRepo, normalizedURIRepo: NormalizedURIRepo) extends SearchUnloadListener(userRepo, normalizedURIRepo) {
  def onEvent: PartialFunction[Event, Unit] = {
    case event @ Event(_, UserEventMetadata(EventFamilies.SEARCH, "searchUnload", _, _, _, metaData, _), _, _) =>

  }
}

