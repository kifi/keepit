package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier

import org.joda.time.DateTime

import reactivemongo.bson.{BSONDocument, BSONDateTime, BSONValue, BSONLong, BSONString, BSONDouble, BSONArray}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{Promise, Future}

trait UserEventLoggingRepo extends BufferedMongoRepo[UserEvent] {
  val warnBufferSize = 500
  val maxBufferSize = 10000

  private def contextToBSON(context: UserEventContext): BSONDocument = {
    BSONDocument(
      context.data.mapValues{ seq =>
        BSONArray(
          seq.map{ _ match {
            case ContextStringData(s)  => BSONString(s)
            case ContextDoubleData(x) => BSONDouble(x)
          }}
        )
      }
    )
  }

  def toBSON(event: UserEvent) : BSONDocument = {
    val userBatch: Long = event.userId / 1000 //Warning: This is a (neccessary!) index optimization. Changing this will require a database change!
    BSONDocument(Seq[(String, BSONValue)](
      "user_batch" -> BSONLong(userBatch),
      "user_id" -> BSONLong(event.userId),
      "context" -> contextToBSON(event.context),
      "event_type" -> BSONString(event.eventType.name),
      "time" -> BSONDateTime(event.time.getMillis)
    ))
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
