package com.keepit.model

import com.keepit.common.healthcheck.AirbrakeNotifier
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsValue, Json, Writes }
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson.{ BSONArray, BSONBoolean, BSONDateTime, BSONDocument }

import scala.concurrent.Future

case class MetricData(dt: DateTime, data: Seq[BSONDocument])

object MetricData {
  import play.modules.reactivemongo.json.ImplicitBSONHandlers._
  implicit val writes = new Writes[MetricData] {
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

  def allLean: Future[Seq[MetricData]] = collection.find(BSONDocument(), BSONDocument("data.users" -> BSONBoolean(false))).cursor.collect[List]().map { docs =>
    docs.map(fromBSON(_))
  }
}

trait MetricRepoFactory {
  def apply(name: String): MetricRepo
  def clear(name: String): Unit
}

class ProdMetricRepoFactory(db: reactivemongo.api.DB, airbrake: AirbrakeNotifier) extends MetricRepoFactory {
  def apply(name: String): MetricRepo = {
    new MetricRepo(db("data4metric_" + name), airbrake)
  }
  def clear(name: String): Unit = db("data4metric_" + name).drop()
}

class DevMetricRepoFactory extends MetricRepoFactory {
  def apply(name: String): MetricRepo = {
    new MetricRepo(null, null)
  }
  def clear(name: String): Unit = {}
}
