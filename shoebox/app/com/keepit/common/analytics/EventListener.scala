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
import com.keepit.inject._
import com.keepit.model._
import com.keepit.search.{ SearchServiceClient, ArticleSearchResultRef, BrowsingHistoryTracker, ClickHistoryTracker }
import com.keepit.common.akka.FortyTwoActor
import akka.actor.ActorSystem
import akka.actor.Props
import play.api.Play.current
import play.api.libs.json.JsObject
import play.api.libs.json.Json

trait EventListenerPlugin extends SchedulingPlugin {
  def onEvent: PartialFunction[Event, Unit]

  case class SearchMeta(query: String, url: String, normUrl: Option[NormalizedURI], queryUUID: Option[ExternalId[ArticleSearchResultRef]])
  def searchParser(externalUser: ExternalId[User], json: JsObject)(implicit s: RSession) = {
    val query = (json \ "query").asOpt[String].getOrElse("")
    val url = (json \ "url").asOpt[String].getOrElse("")
    val user = inject[UserRepo].get(externalUser)
    val normUrl = inject[NormalizedURIRepo].getByNormalizedUrl(url)
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
class KifiResultClickedListener extends EventListenerPlugin {
  private lazy val searchServiceClient = inject[SearchServiceClient]
  private lazy val clickHistoryTracker = inject[ClickHistoryTracker]

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, "kifiResultClicked", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, meta, bookmark) = inject[Database].readOnly { implicit s =>
        val (user, meta) = searchParser(externalUser, metaData)
        val bookmarkRepo = inject[BookmarkRepo]
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
class UsefulPageListener extends EventListenerPlugin {
  private lazy val browsingHistoryTracker = inject[BrowsingHistoryTracker]

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "usefulPage", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, url, normUrl) = inject[Database].readOnly { implicit s =>
        val user = inject[UserRepo].get(externalUser)
        val url = (metaData \ "url").asOpt[String].getOrElse("")
        val normUrl = inject[NormalizedURIRepo].getByNormalizedUrl(url)
        (user, url, normUrl)
      }
      normUrl.foreach(n => browsingHistoryTracker.add(user.id.get, n.id.get))
  }
}

@Singleton
class SliderShownListener extends EventListenerPlugin {
  private lazy val sliderHistoryTracker = inject[SliderHistoryTracker]

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "sliderShown", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, normUri) = inject[Database].readWrite(attempts = 2) { implicit s =>
        val user = inject[UserRepo].get(externalUser)
        val normUri = (metaData \ "url").asOpt[String].map { url =>
          val repo = inject[NormalizedURIRepo]
          repo.getByNormalizedUrl(url).getOrElse(repo.save(NormalizedURIFactory(url, NormalizedURIStates.ACTIVE)))
        }
        (user, normUri)
      }
      normUri.foreach(n => sliderHistoryTracker.add(user.id.get, n.id.get))
  }
}

@Singleton
class SearchUnloadListener @Inject() (persistEventPlugin: PersistEventPlugin, store: MongoEventStore) extends EventListenerPlugin {
  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SEARCH, "searchUnload", externalUser, _, experiments, metaData, _), _, _) => {
      val kifiClicks = (metaData \ "kifiResultsClicked").asOpt[Int].getOrElse(-1)
      val googleClicks = (metaData \ "googleResultsClicked").asOpt[Int].getOrElse(-1)
      val uuid = (metaData \ "queryUUID").asOpt[String].getOrElse("")
      val q = MongoSelector(EventFamilies.SERVER_SEARCH).withEventName("search_return_hits").withMetaData("queryUUID", uuid)
      val cursor = store.find(q)
      if (cursor.size == 1) {
        val data = cursor.map(dbo => EventSerializer.eventSerializer.mongoReads(dbo).get).next.metaData
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

