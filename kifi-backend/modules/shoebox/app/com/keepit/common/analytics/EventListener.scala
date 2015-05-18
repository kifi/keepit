package com.keepit.common.analytics

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.normalizer.{ NormalizedURIInterner, NormalizationCandidate }

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
class SliderShownListener @Inject() (
  userRepo: UserRepo,
  normalizedURIRepo: NormalizedURIRepo,
  normalizedURIInterner: NormalizedURIInterner,
  db: Database,
  sliderHistoryTracker: SliderHistoryTracker)
    extends EventListener(userRepo, normalizedURIRepo) {

  def onEvent: PartialFunction[Event, Unit] = {
    case Event(_, UserEventMetadata(EventFamilies.SLIDER, "sliderShown", externalUser, _, experiments, metaData, _), _, _) =>
      val (user, normUri) = db.readWrite(attempts = 2) { implicit s =>
        val user = userRepo.get(externalUser)
        val normUri = (metaData \ "url").asOpt[String].map { url =>
          normalizedURIInterner.internByUri(url, contentWanted = false, candidates = NormalizationCandidate.fromJson(metaData))
        }
        (user, normUri)
      }
      normUri.foreach(n => sliderHistoryTracker.add(user.id.get, n.id.get))
  }
}
