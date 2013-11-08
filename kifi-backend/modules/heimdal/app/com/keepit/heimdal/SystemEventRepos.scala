package com.keepit.heimdal

import reactivemongo.bson.{BSONDocument, BSONArray}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{Promise, Future}
import com.keepit.common.healthcheck.AirbrakeNotifier

trait SystemEventLoggingRepo extends BufferedMongoRepo[SystemEvent] {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: SystemEvent) : BSONDocument = {
    // val userBatch: Long = event.userId / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    BSONDocument(EventRepo.eventToBSONFields(event))
  }

  def fromBSON(bson: BSONDocument): SystemEvent = ???
}

class ProdSystemEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends SystemEventLoggingRepo

class DevSystemEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends SystemEventLoggingRepo {
  override def insert(obj: SystemEvent, dropDups: Boolean = false) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}
