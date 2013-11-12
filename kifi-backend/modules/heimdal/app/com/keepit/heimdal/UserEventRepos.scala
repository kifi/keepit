package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier


import reactivemongo.bson.{BSONDocument, BSONLong, BSONArray}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{Promise, Future}

abstract class UserEventLoggingRepo extends MongoEventRepo[UserEvent] {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  def toBSON(event: UserEvent) : BSONDocument = {
    val userBatch: Long = event.userId / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    val fields = EventRepo.eventToBSONFields(event) ++ Seq(
      "user_batch" -> BSONLong(userBatch),
      "user_id" -> BSONLong(event.userId)
    )
    BSONDocument(fields)
  }

  def fromBSON(bson: BSONDocument): UserEvent = ???
}

class ProdUserEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends UserEventLoggingRepo

class DevUserEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends UserEventLoggingRepo {
  override def insert(obj: UserEvent, dropDups: Boolean = false) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}

trait UserEventDescriptorRepo extends EventDescriptorRepo[UserEvent]
class ProdUserEventDescriptorRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends ProdEventDescriptorRepo[UserEvent] with UserEventDescriptorRepo
class DevUserEventDescriptorRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends DevEventDescriptorRepo[UserEvent] with UserEventDescriptorRepo
