package com.keepit.heimdal

import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import reactivemongo.bson._
import reactivemongo.core.commands.{PipelineOperator, Aggregate, LastError}

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import reactivemongo.bson.BSONDouble
import reactivemongo.bson.BSONString
import reactivemongo.api.collections.default.BSONCollection
import org.joda.time.DateTime

//Might want to change this to a custom play one
import java.util.concurrent.atomic.{AtomicLong, AtomicBoolean}

import play.modules.statsd.api.Statsd

case class MongoInsertBufferFullException() extends java.lang.Throwable

trait MongoRepo[T] {
  protected val airbrake: AirbrakeNotifier

  val collection: BSONCollection
  def toBSON(obj: T): BSONDocument
  def fromBSON(bson: BSONDocument) : T

  private def handleError(doc: BSONDocument, lastError: LastError, dropDups: Boolean = false) : Unit = {
    if (lastError.ok==false && (!dropDups || (lastError.code.isDefined && lastError.code.get!=11000))) {
      airbrake.notify(AirbrakeError(
        exception = lastError.fillInStackTrace,
        message = Some(s"Error inserting $doc into MongoDB")
      ))
    }
  }

  protected def safeInsert(doc: BSONDocument, dropDups: Boolean = false) = {
    val insertionFuture = collection.insert(doc) //Non safe future on purpose! (-Stephen)
    insertionFuture.onFailure{
      case lastError : LastError => handleError(doc, lastError, dropDups)
      case ex: Throwable => airbrake.notify(AirbrakeError(
        exception = ex,
        message = Some(s"Error inserting $doc into MongoDB")
      ))
    }
    insertionFuture.map{ lastError =>
      handleError(doc, lastError, dropDups)
      lastError
    }

  }

  def insert(obj: T, dropDups: Boolean = false) : Unit = {
    val bson = toBSON(obj)
    safeInsert(bson, dropDups)
  }

  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    val collectionName = collection.name
    val db = collection.db
    db.command(Aggregate(collectionName,command))
  }

  def all: Future[Seq[T]] = collection.find(BSONDocument()).cursor.collect[List]().map{ docs =>
    docs.map(fromBSON(_))
  }
}

trait BufferedMongoRepo[T] extends MongoRepo[T] { //Convoluted?
  val warnBufferSize: Int
  val maxBufferSize: Int

  val bufferSize = new AtomicLong(0)
  val hasWarned = new AtomicBoolean(false)

  override def insert(obj: T, dropDups: Boolean = false) : Unit = {
    if (bufferSize.get>=maxBufferSize) {
      airbrake.notify(s"Mongo Insert Buffer Full! (${bufferSize.get})")
      throw MongoInsertBufferFullException()
    } else if (bufferSize.get>=warnBufferSize && hasWarned.getAndSet(true)==false) {
      airbrake.notify(s"Mongo Insert almost Buffer Full. (${bufferSize.get})")
    } else if (bufferSize.get < warnBufferSize) hasWarned.set(false)

    val inflight = bufferSize.incrementAndGet()
    Statsd.gauge(s"monogInsertBuffer.${collection.name}", inflight)
    safeInsert(toBSON(obj)).map{ lastError =>
      bufferSize.decrementAndGet()
      if (lastError.ok==false && (!dropDups || (lastError.code.isDefined && lastError.code.get!=11000))) insert(obj)
    }

  }
}

object CustomBSONHandlers {
  implicit object BSONDateTimeHandler extends BSONHandler[BSONDateTime, DateTime] {
    def read(time: BSONDateTime) = new DateTime(time.value)
    def write(jdtime: DateTime) = BSONDateTime(jdtime.getMillis)
  }

  implicit object BSONEventTypeHandler extends BSONHandler[BSONString, EventType] {
    def read(name: BSONString) = EventType(name.value)
    def write(eventType: EventType) = BSONString(eventType.name)
  }

  implicit object BSONContextDataHandler extends BSONHandler[BSONArray, ContextData] {
    def write(data: ContextData) = data match {
      case ContextList(values) => BSONArray(values.map(writeSimpleContextData))
      case data: SimpleContextData => BSONArray(writeSimpleContextData(data))
    }

    private def writeSimpleContextData(data: SimpleContextData): BSONValue = data match {
      case ContextStringData(value) => BSONString(value)
      case ContextDoubleData(value) => BSONDouble(value)
      case ContextBoolean(value) => BSONBoolean(value)
      case ContextDate(value) => BSONDateTimeHandler.write(value)
    }

    def read(doc: BSONArray): ContextData = ???
  }

  implicit object BSONEventContextHandler extends BSONHandler[BSONDocument, HeimdalContext] {
    def write(context: HeimdalContext) = BSONDocument(context.data.mapValues(BSONContextDataHandler.write))
    def read(doc: BSONDocument): HeimdalContext = ???
  }
}
