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
import com.keepit.common.plugin.SchedulingPlugin
import com.keepit.common.actor.ActorFactory
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices
import com.keepit.model._
import com.keepit.search.{ SearchServiceClient, ArticleSearchResultRef, BrowsingHistoryTracker, ClickHistoryTracker }
import com.keepit.common.akka.FortyTwoActor
import akka.actor.ActorSystem
import akka.actor.Props
import play.api.libs.json.JsObject
import play.api.libs.json.Json

abstract class EventListenerPlugin(
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo)
  extends SchedulingPlugin {

  def onEvent: PartialFunction[Event, Unit]

  case class SearchMeta(query: String, url: String, normUrl: Option[NormalizedURI], queryUUID: Option[ExternalId[ArticleSearchResultRef]])

  def searchParser(externalUser: ExternalId[User], json: JsObject)(implicit s: RSession) = {
    val query = (json \ "query").asOpt[String].getOrElse("")
    val url = (json \ "url").asOpt[String].getOrElse("")
    val user = userRepo.get(externalUser)
    val normUrl = normalizedURIRepo.getByNormalizedUrl(url)
    val queryUUID = ExternalId.asOpt[ArticleSearchResultRef]((json \ "queryUUID").asOpt[String].getOrElse(""))
    (user, SearchMeta(query, url, normUrl, queryUUID))
  }
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
class KifiResultClickedListener @Inject() (
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo,
    searchServiceClient: SearchServiceClient,
    clickHistoryTracker: ClickHistoryTracker,
    db: Database,
    bookmarkRepo: BookmarkRepo)
  extends EventListenerPlugin(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, "kifiResultClicked", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, meta, bookmark) = db.readOnly { implicit s =>
        val (user, meta) = searchParser(externalUser, metaData)
        val bookmark = meta.normUrl.map(n => bookmarkRepo.getByUriAndUser(n.id.get, user.id.get)).flatten
        (user, meta, bookmark)
      }
      meta.normUrl.foreach { n =>
        searchServiceClient.logResultClicked(user.id.get, meta.query, n.id.get, !bookmark.isEmpty)
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
    userRepo: UserRepo,
    normalizedURIRepo: NormalizedURIRepo,
    persistEventPlugin: PersistEventPlugin,
    store: MongoEventStore,
    implicit private val clock: Clock,
    implicit private val fortyTwoServices: FortyTwoServices)
  extends SearchUnloadListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, "searchUnload", externalUser, _, experiments, metaData, _), _, _) => {
      val kifiClicks = (metaData \ "kifiResultsClicked").asOpt[Int].getOrElse(-1)
      val googleClicks = (metaData \ "googleResultsClicked").asOpt[Int].getOrElse(-1)
      val uuid = (metaData \ "queryUUID").asOpt[String].getOrElse("")
      val q = MongoSelector(EventFamilies.SERVER_SEARCH).withEventName("search_return_hits").withMetaData("queryUUID", uuid)
      store.find(q).map { dbo =>
        val data = EventSerializer.eventSerializer.mongoReads(dbo).get.metaData
        val svVar = (data.metaData \ "svVariance").asOpt[Double].getOrElse(-1.0) // retrieve the related semantic variance
        val newMetaData = Json.obj("queryUUID" -> uuid,
          "svVariance" -> svVar,
          "kifiResultsClicked" -> kifiClicks,
          "googleResultsClicked" -> googleClicks)

        val event = Events.serverEvent(EventFamilies.SERVER_SEARCH, "search_statistics", newMetaData)
        persistEventPlugin.persist(event)
      }
    }
  }
}

class FakeSearchUnloadListenerImpl @Inject() (userRepo: UserRepo, normalizedURIRepo: NormalizedURIRepo) extends SearchUnloadListener(userRepo, normalizedURIRepo) {
  def onEvent: PartialFunction[Event, Unit] = PartialFunction.empty
}

