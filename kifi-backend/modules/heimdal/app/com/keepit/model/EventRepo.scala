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
