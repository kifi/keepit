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
  def descriptors: EventDescriptorRepo[E]
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

abstract class MongoEventRepo[E <: HeimdalEvent: HeimdalEventCompanion] extends EventRepo[E] {
  val getEventCompanion = implicitly[HeimdalEventCompanion[E]]
  val mixpanel: MixpanelClient
  def persist(event: E): Future[Unit] = {
    val trackF = descriptors.getByName(event.eventType) flatMap {
      case None => descriptors.upsert(EventDescriptor(event.eventType)) map (_ => ())
      case Some(description) if description.mixpanel => mixpanel.track(event)
    }

    trackF
  }
}

abstract class DevEventRepo[E <: HeimdalEvent: HeimdalEventCompanion] extends EventRepo[E] {
  val getEventCompanion = implicitly[HeimdalEventCompanion[E]]
  def persist(event: E): Future[Unit] = Future.successful(())
  lazy val descriptors: EventDescriptorRepo[E] = new DevEventDescriptorRepo[E] {}
}

object EventRepo {
  def findByEventTypeCode(availableRepos: EventRepo[_ <: HeimdalEvent]*)(code: String): Option[EventRepo[_ <: HeimdalEvent]] = availableRepos.find(_.getEventCompanion == HeimdalEventCompanion.byTypeCode(code))
}

