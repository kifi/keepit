package com.keepit.model

import com.keepit.common.akka.SafeFuture
import com.keepit.heimdal.{ EventDescriptor, EventType, HeimdalEvent }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

trait EventDescriptorRepo[E <: HeimdalEvent] {
  def upsert(descriptor: EventDescriptor): Future[Int]
  def getByName(name: EventType): Future[Option[EventDescriptor]]
  def getAll(): Future[Seq[EventDescriptor]]
}

trait ProdEventDescriptorRepo[E <: HeimdalEvent] extends MongoRepo[EventDescriptor] with EventDescriptorRepo[E] {
  def getAll(): Future[Seq[EventDescriptor]] = Future.successful(Seq.empty)
}

trait DevEventDescriptorRepo[E <: HeimdalEvent] extends EventDescriptorRepo[E] {
  def upsert(obj: EventDescriptor): Future[Int] = Future.failed(new NotImplementedError)
  def getByName(name: EventType): Future[Option[EventDescriptor]] = Future.successful(None)
  def getAll(): Future[Seq[EventDescriptor]] = Future.successful(Seq.empty)
}
