package com.keepit.heimdal


import com.keepit.common.healthcheck.HealthcheckPlugin

import org.joda.time.DateTime

import reactivemongo.bson.{BSONDocument, BSONDateTime, BSONValue, BSONLong, BSONString, BSONDouble, BSONArray}
import reactivemongo.api.collections.default.BSONCollection




trait UserEventLoggingRepo extends BufferedMongoRepo[UserEvent] {
  val warnBufferSize = 1000
  val maxBufferSize = 2000

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

class ProdUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo

class DevUserEventLoggingRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends UserEventLoggingRepo {
  override def insert(obj: UserEvent) : Unit = {}
}
