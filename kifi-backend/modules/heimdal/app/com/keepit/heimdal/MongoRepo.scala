package com.keepit.heimdal

import com.keepit.common.healthcheck.{HealthcheckPlugin, HealthcheckError, Healthcheck}
import com.keepit.common.akka.SafeFuture


import reactivemongo.bson.BSONDocument
import reactivemongo.api.collections.default.BSONCollection

import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global //Might want to change this to a custom play one
import java.util.concurrent.atomic.AtomicLong

case class MongoInsertBufferFullException() extends java.lang.Throwable

trait MongoRepo[T] {
  protected val healthcheckPlugin: HealthcheckPlugin

  val collection: BSONCollection
  def toBSON(obj: T): BSONDocument
  def fromBSON(bson: BSONDocument)

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
}

trait BufferedMongoRepo[T] extends MongoRepo[T] { //Convoluted?
  val warnBufferSize: Int
  val maxBufferSize: Int

  val bufferSize = new AtomicLong(0)

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
    }
    if (bufferSize.get>=warnBufferSize) {
      healthcheckPlugin.addError(HealthcheckError(
        error = None, 
        method = Some("mongo"), 
        path = None, 
        callType = Healthcheck.INTERNAL,
        errorMessage = Some(s"Mongo Insert almost Buffer Full. (${bufferSize.get})")
      ))
    }

    bufferSize.incrementAndGet()
    safeInsert(toBSON(obj)).map{ lastError =>
      bufferSize.decrementAndGet() 
      if (lastError.ok==false) insert(obj)
    }
  
  }

}

trait UserEventLoggingRepo extends BufferedMongoRepo[UserEvent] {
  val warnBufferSize = 1000
  val maxBufferSize = 2000

  def toBSON(obj: UserEvent) = ??? //XXX
  def fromBSON(bson: BSONDocument) = ???
}

class ProdUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo

class DevUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo {
  override def insert(obj: UserEvent) : Unit = {}
}
 







