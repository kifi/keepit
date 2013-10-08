package com.keepit.heimdal

import com.keepit.common.healthcheck.HealthcheckPlugin

import reactivemongo.core.commands.PipelineOperator
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{BSONDocument, BSONArray, BSONDateTime, Macros, BSONHandler}

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import org.joda.time.DateTime

import scala.concurrent.{Future, Promise}




trait MetricDescriptorRepo extends MongoRepo[MetricDescriptor] {

  implicit object BSONDateTimeHandler extends BSONHandler[BSONDateTime, DateTime] {
    def read(time: BSONDateTime) = new DateTime(time.value)
    def write(jdtime: DateTime) = BSONDateTime(jdtime.getMillis)
  }

  val bsonHandler = Macros.handler[MetricDescriptor]

  def toBSON(obj: MetricDescriptor): BSONDocument = bsonHandler.write(obj)
  def fromBSON(doc: BSONDocument): MetricDescriptor = bsonHandler.read(doc)

  def upsert(obj: MetricDescriptor) : Unit

  def getByName(name: String): Future[Option[MetricDescriptor]] = {
    collection.find(BSONDocument("name" -> name)).one.map{
      _.map(fromBSON(_))
    }
  }
}


class ProdMetricDescriptorRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends MetricDescriptorRepo {
  def upsert(obj: MetricDescriptor) : Unit = {
    collection.uncheckedUpdate(
      BSONDocument("name" -> obj.name),
      toBSON(obj),
      true,
      false
    )
  }
}

class DevMetricDescriptorRepo(val collection: BSONCollection, protected val healthcheckPlugin: HealthcheckPlugin) extends MetricDescriptorRepo {
  def upsert(obj: MetricDescriptor) : Unit = {}
  override def insert(obj: MetricDescriptor) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}
