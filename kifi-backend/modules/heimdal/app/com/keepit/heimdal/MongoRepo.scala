package com.keepit.heimdal

import com.keepit.common.healthcheck.{HealthcheckPlugin, HealthcheckError, Healthcheck}

import reactivemongo.bson.BSONDocument
import reactivemongo.api.collections.default.BSONCollection

import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global //Might want to change this to a custom play one
import scala.collection.mutable.SynchronizedQueue

case class MongoInsertBufferFullException() extends java.lang.Throwable

trait MongoRepo[T] {
  protected val healthcheckPlugin: HealthcheckPlugin

  val collection: BSONCollection
  def toBSON(obj: T): BSONDocument
  def fromBSON(bson: BSONDocument)

  protected def safeInsert(doc: BSONDocument) = {
    collection.insert(doc).map{ lastError =>
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
  val bufferSize: Int

  val buffer = new SynchronizedQueue[T]()

  override def insert(obj: T) : Unit = {
    if (buffer.length>=bufferSize) {
      healthcheckPlugin.addError(HealthcheckError(
        error = None, 
        method = Some("mongo"), 
        path = None, 
        callType = Healthcheck.INTERNAL,
        errorMessage = Some(s"Mongo Insert Buffer Full! ($bufferSize)")
      ))
      throw MongoInsertBufferFullException()
    } else {
      buffer += obj
      val docOpt = buffer.dequeueFirst(_ => true)
      docOpt.foreach{ doc => safeInsert(toBSON(doc)).map{ lastError => 
        if (lastError.ok==false) insert(doc)
      }}
    }
  }
}

trait UserEventLoggingRepo extends BufferedMongoRepo[UserEvent] {
  val bufferSize = 10000

  def toBSON(obj: UserEvent) = ??? //XXX
  def fromBSON(bson: BSONDocument) = ???
}

class ProdUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo

class DevUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo {
  override def insert(obj: UserEvent) : Unit = {}
}
 







