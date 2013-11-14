package com.keepit.heimdal

import reactivemongo.bson.{BSONDocument, Macros}

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import com.keepit.common.time._
import com.keepit.common.akka.SafeFuture
import CustomBSONHandlers._

trait EventDescriptorRepo[E <: HeimdalEvent] {
  def upsert(descriptor: EventDescriptor) : Future[Int]
  def getByName(name: EventType): Future[Option[EventDescriptor]]
  def getAll(): Future[Seq[EventDescriptor]]
}

trait ProdEventDescriptorRepo[E <: HeimdalEvent] extends MongoRepo[EventDescriptor] with EventDescriptorRepo[E] {

  val handler = Macros.handler[EventDescriptor]

  def toBSON(obj: EventDescriptor): BSONDocument = handler.write(obj)
  def fromBSON(doc: BSONDocument): EventDescriptor = handler.read(doc)

  override def insert(obj: EventDescriptor, dropDups: Boolean = false) : Unit = { upsert(obj) } // Do not allow unchecked inserts of descriptors
  def upsert(obj: EventDescriptor) : Future[Int] = new SafeFuture(
      collection.update(
        selector = BSONDocument("name" -> obj.name),
        update = toBSON(obj.copy(updatedAt = currentDateTime)),
        upsert = true,
        multi = false
      ) map { lastError => if (lastError.inError) throw lastError.getCause else lastError.updated }
    )

  def getByName(name: EventType): Future[Option[EventDescriptor]] = {
    collection.find(BSONDocument("name" -> name)).one.map{
      _.map(fromBSON(_))
    }
  }

  def getAll(): Future[Seq[EventDescriptor]] = all
}

trait DevEventDescriptorRepo[E <: HeimdalEvent] extends EventDescriptorRepo[E] {
  def upsert(obj: EventDescriptor) : Future[Int] = Future.failed(new NotImplementedError)
  def getByName(name: EventType): Future[Option[EventDescriptor]] = Future.successful(None)
  def getAll(): Future[Seq[EventDescriptor]] = Future.successful(Seq.empty)
}
