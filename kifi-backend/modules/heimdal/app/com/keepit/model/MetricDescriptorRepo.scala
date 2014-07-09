package com.keepit.model

import com.keepit.common.healthcheck.AirbrakeNotifier
import reactivemongo.core.commands.{LastError, PipelineOperator}
import com.keepit.heimdal.MetricDescriptor
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{BSONDocument, BSONArray, Macros}
import CustomBSONHandlers._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Future, Promise}
import com.keepit.common.akka.SafeFuture

trait MetricDescriptorRepo extends MongoRepo[MetricDescriptor] {

  val bsonHandler = Macros.handler[MetricDescriptor]

  def toBSON(obj: MetricDescriptor): BSONDocument = bsonHandler.write(obj)
  def fromBSON(doc: BSONDocument): MetricDescriptor = bsonHandler.read(doc)

  def upsert(obj: MetricDescriptor) : Future[LastError]

  def getByName(name: String): Future[Option[MetricDescriptor]] = {
    collection.find(BSONDocument("name" -> name)).one.map{
      _.map(fromBSON(_))
    }
  }
}

class ProdMetricDescriptorRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends MetricDescriptorRepo {
  def upsert(obj: MetricDescriptor) : Future[LastError] = new SafeFuture(
    collection.update(
      selector = BSONDocument("name" -> obj.name),
      update = toBSON(obj),
      upsert = true,
      multi = false
    ) map { lastError => if (lastError.inError) throw lastError.getCause else lastError }
  )
}

class DevMetricDescriptorRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends MetricDescriptorRepo {
  def upsert(obj: MetricDescriptor) : Future[LastError] = Future.failed(new NotImplementedError)
  override def insert(obj: MetricDescriptor, dropDups: Boolean = false) : Unit = {}
  override def performAggregation(command: Seq[PipelineOperator]): Future[Stream[BSONDocument]] = {
    Promise.successful(
      Stream(BSONDocument("command" -> BSONArray(command.map(_.makePipe))))
    ).future
  }
}
