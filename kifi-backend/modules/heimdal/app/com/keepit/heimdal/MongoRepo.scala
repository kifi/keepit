package com.keepit.heimdal

import com.keepit.common.healthcheck.{HealthcheckPlugin, HealthcheckError, Healthcheck}
import com.keepit.common.akka.SafeFuture


import reactivemongo.bson.BSONDocument
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.core.commands.{PipelineOperator, Aggregate}

import scala.concurrent.{Promise, Future}
import play.api.libs.concurrent.Execution.Implicits.defaultContext //Might want to change this to a custom play one
import java.util.concurrent.atomic.{AtomicLong, AtomicBoolean}

import play.modules.statsd.api.Statsd

case class MongoInsertBufferFullException() extends java.lang.Throwable

trait MongoRepo[T] {
  protected val healthcheckPlugin: HealthcheckPlugin

  val collection: BSONCollection
  def toBSON(obj: T): BSONDocument
  def fromBSON(bson: BSONDocument) : T

  protected def safeInsert(doc: BSONDocument) = {
    val insertionFuture = new SafeFuture(collection.insert(doc))
    insertionFuture.map{ lastError =>
      if (lastError.ok==false) {
        healthcheckPlugin.addError(HealthcheckError(
          error = Some(lastError.fillInStackTrace), 
          method = Some("mongo"), 
          path = None, 
          callType = Healthcheck.INTERNAL,
          errorMessage = Some(s"Error inserting into MongoDB (${lastError.errMsg})")
        ))
      }
      lastError
    }
  }

  def insert(obj: T) : Unit = {
    val bson = toBSON(obj)
    safeInsert(bson)
  }

  def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    val collectionName = collection.name
    val db = collection.db
    db.command(Aggregate(collectionName,command)) 
  }

  def all: Future[Seq[T]] = collection.find(BSONDocument()).cursor.toList.map{ docs =>
    docs.map(fromBSON(_))
  }
}

trait BufferedMongoRepo[T] extends MongoRepo[T] { //Convoluted?
  val warnBufferSize: Int
  val maxBufferSize: Int

  val bufferSize = new AtomicLong(0)
  val hasWarned = new AtomicBoolean(false)

  override def insert(obj: T) : Unit = {
    if (bufferSize.get>=maxBufferSize) {
      healthcheckPlugin.addError(HealthcheckError(
        error = None, 
        method = Some("mongo"), 
        path = None, 
        callType = Healthcheck.INTERNAL,
        errorMessage = Some(s"Mongo Insert Buffer Full! (${bufferSize.get})")
      ))
      throw MongoInsertBufferFullException()
    } else if (bufferSize.get>=warnBufferSize && hasWarned.getAndSet(true)==false) {
      healthcheckPlugin.addError(HealthcheckError(
        error = None, 
        method = Some("mongo"), 
        path = None, 
        callType = Healthcheck.INTERNAL,
        errorMessage = Some(s"Mongo Insert almost Buffer Full. (${bufferSize.get})")
      ))
    } else if (bufferSize.get < warnBufferSize) hasWarned.set(false)

    val inflight = bufferSize.incrementAndGet()
    Statsd.gauge(s"monogInsertBuffer.${collection.name}", inflight)
    safeInsert(toBSON(obj)).map{ lastError =>
      bufferSize.decrementAndGet() 
      if (lastError.ok==false) insert(obj)
    }
  
  }

}


 







