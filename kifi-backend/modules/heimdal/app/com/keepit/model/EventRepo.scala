package com.keepit.model

import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.heimdal._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, Json }

import scala.concurrent.{ Future, Promise }

trait EventRepo[E <: HeimdalEvent] {
  def persist(event: E): Future[Unit]
  def getEventCompanion: HeimdalEventCompanion[E]
  def descriptors: Set[EventType]
}

trait EventAugmentor[E <: HeimdalEvent] extends PartialFunction[E, Future[Seq[(String, ContextData)]]]

object EventAugmentor extends Logging {
  def safelyAugmentContext[E <: HeimdalEvent](event: E, augmentors: EventAugmentor[E]*): Future[HeimdalContext] = {
    val safeAugmentations = augmentors.collect {
      case augmentor if augmentor.isDefinedAt(event) =>
        augmentor(event) recover {
          case ex =>
            log.error(s"An augmentor failed on event: ${event.eventType}", ex)
            Seq.empty
        }
    }

    Future.sequence(safeAugmentations).map { augmentations =>
      val contextBuilder = new HeimdalContextBuilder()
      contextBuilder.data ++= event.context.data
      augmentations.foreach(contextBuilder.data ++= _)
      contextBuilder.build
    }
  }
}

abstract class MongoEventRepo[E <: HeimdalEvent: HeimdalEventCompanion] extends EventRepo[E] with Logging {
  val getEventCompanion = implicitly[HeimdalEventCompanion[E]]
  val mixpanel: MixpanelClient
  def persist(event: E): Future[Unit] = {
    if (descriptors.contains(event.eventType)) {
      mixpanel.track(event)
    } else {
      log.info(s"[EventRepo] Event discarded: $event")
      Future.successful((): Unit)
    }
  }
}

abstract class DevEventRepo[E <: HeimdalEvent: HeimdalEventCompanion] extends EventRepo[E] {
  val getEventCompanion = implicitly[HeimdalEventCompanion[E]]
  def persist(event: E): Future[Unit] = Future.successful(())
}

object EventRepo {
  def findByEventTypeCode(availableRepos: EventRepo[_ <: HeimdalEvent]*)(code: String): Option[EventRepo[_ <: HeimdalEvent]] = availableRepos.find(_.getEventCompanion == HeimdalEventCompanion.byTypeCode(code))
}

