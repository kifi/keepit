package com.keepit.heimdal

import org.joda.time.DateTime

import com.keepit.common.healthcheck.AirbrakeNotifier

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{JsObject, JsNull, JsArray, Json, Writes, JsValue}

import reactivemongo.bson.{BSONDocument, BSONDateTime, BSONArray}
import reactivemongo.api.collections.default.BSONCollection

import scala.concurrent.Await
import scala.concurrent.duration._


case class MetricData(dt: DateTime, data: Seq[BSONDocument])

object MetricData {
  import play.modules.reactivemongo.json.ImplicitBSONHandlers._
  implicit val writes = new Writes[MetricData]{
    def writes(obj: MetricData): JsValue = {
      Json.obj(
        "time" -> obj.dt.getMillis,
        "data" -> obj.data.map(JsObjectReader.read(_))
      )
    }
  }
}

class MetricRepo(val collection: BSONCollection, protected val airbrake: AirbrakeNotifier) extends MongoRepo[MetricData] {
  def toBSON(obj: MetricData): BSONDocument = BSONDocument(
    "time" -> BSONDateTime(obj.dt.getMillis),
    "data" -> BSONArray(obj.data)
  )
  def fromBSON(doc: BSONDocument): MetricData = MetricData(
    new DateTime(doc.getAs[BSONDateTime]("time").get.value),
    doc.getAs[BSONArray]("data").get.values.toSeq.map(_.asInstanceOf[BSONDocument])
  )
}


trait MetricRepoFactory {
  def apply(name: String): MetricRepo
  def clear(name: String): Unit
}

class ProdMetricRepoFactory(db: reactivemongo.api.DB, airbrake: AirbrakeNotifier) extends MetricRepoFactory{
  def apply(name: String): MetricRepo = {
    new MetricRepo(db("data4metric_" + name), airbrake)
  }
  def clear(name: String): Unit = db("data4metric_" + name).drop()
}

class DevMetricRepoFactory extends MetricRepoFactory{
  def apply(name: String): MetricRepo = {
    new MetricRepo(null,null)
  }
  def clear(name: String): Unit = {}
}
