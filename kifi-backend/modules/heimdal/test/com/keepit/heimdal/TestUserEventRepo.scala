package com.keepit.heimdal

import com.keepit.common.healthcheck.AirbrakeNotifier

import org.joda.time.DateTime

import reactivemongo.bson.{BSONDocument, BSONDateTime, BSONValue, BSONLong, BSONString, BSONDouble, BSONArray}
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.PipelineOperator

import scala.concurrent.{Promise, Future}


class TestUserEventLoggingRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends UserEventLoggingRepo {

  var events: List[UserEvent] = Nil

  def eventCount(): Int = events.length

  def lastEvent(): UserEvent = events.head

  override def insert(obj: UserEvent, dropDups: Boolean = false) : Unit = synchronized {
    events = obj::events
  }

  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}
