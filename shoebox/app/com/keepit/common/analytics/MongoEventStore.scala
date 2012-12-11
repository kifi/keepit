package com.keepit.common.analytics

import com.keepit.common.db.ExternalId
import play.api.libs.json.Format
import com.keepit.serializer.EventSerializer
import com.mongodb.casbah.MongoConnection
import com.amazonaws.services.s3.AmazonS3
import com.mongodb.casbah.Imports._
import com.keepit.common.logging.Logging
import com.mongodb.casbah.commons.conversions.scala._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.model.User
import play.api.libs.json._
import play.api.libs.json.Json._

trait MongoEventStore {
  def save(event: Event): Unit
  def save(eventFamily: EventFamily, dbObject: DBObject): Unit
}

case class MongoSelector(eventFamily: EventFamily) {
  private val builder = MongoDBObject.newBuilder
  private var q = MongoDBObject()

  def ofParentEvent(eventId: ExternalId[Event]) = {
    q = q ++ ("metaData.prevEvents" $in eventId.id)
  }
  def withId(externalId: ExternalId[Event]) = {
    q = q ++ ("id" -> externalId.id)
    this
  }
  def withUser(externalId: ExternalId[User]) = {
    q = q ++ ("metaData.userId" -> externalId.id)
    this
  }
  def withEventName(name: String) = {
    q = q ++ ("metaData.eventName" -> name)
    this
  }
  def withMetaData(field: String) = {
    q = q ++ ("metaData.%s".format(field) $exists true)
    this
  }
  def withMetaData(field: String, value: String) = {
    q = q ++ ("metaData.%s".format(field) -> value)
    this
  }
  def withDateRange(startDate: DateTime, endDate: DateTime) = {
    q = q ++ ("createdAt" $gte startDate $lte endDate)
    this
  }
  def withMinDate(startDate: DateTime) = {
    q = q ++ ("createdAt" $gte startDate)
    this
  }
  def withMaxDate(endDate: DateTime) = {
    q = q ++ ("createdAt" $lte endDate)
    this
  }

  def build = q
  override def toString = q.toString
}

trait MongoFunc {
  val js: String
}

case class MongoMapFunc(js: String) extends MongoFunc
object MongoMapFunc {
  val DATE_BY_HOUR = MongoMapFunc("""
    function(doc) {
      var date = new Date(doc.createdAt);
      var dateKey = (date.getMonth()+1)+"/"+date.getDate()+"/"+date.getFullYear()+' '+date.getHours()+':00';
      return {'day':dateKey};
    }
    """)
  val DATE = MongoMapFunc("""
    function(doc) {
      var date = new Date(doc.createdAt);
      var dateKey = (date.getMonth()+1)+"/"+date.getDate()+"/"+date.getFullYear()+'';
      return {'day':dateKey};
    }
    """)
}

case class MongoReduceFunc(js: String, initial: DBObject = DBObject()) extends MongoFunc
object MongoReduceFunc {
  val AGGREGATE = MongoReduceFunc("""function(obj, prev) {prev.count++;}""", DBObject("count" -> 0))
}

class MongoEventStoreImpl(val mongoDB: MongoDB) extends MongoEventStore with Logging {
  RegisterJodaTimeConversionHelpers()
  implicit def MongoSelectorToDBObject(m: MongoSelector): DBObject = m.build

  def save(event: Event): Unit = {
    save(event.metaData.eventFamily, EventSerializer.eventSerializer.mongoWrites(event))
  }

  def save(eventFamily: EventFamily, dbObject: DBObject): Unit = {
    val coll = mongoDB(eventFamily.name)
    val result = coll.insert(dbObject)
    log.info(result)
  }

  def queryByEventName(eventFamily: EventFamily, eventName: String): Stream[Event] = {
    val q = MongoDBObject("metaData.eventName" -> eventName)
    cursorToEvent(query(eventFamily, q)) toStream
  }

  def countByEventName(eventFamily: EventFamily, eventName: String): Int = {
    val q = MongoDBObject("metaData.eventName" -> eventName)
    query(eventFamily, q).count
  }

  def countGroup(eventFamily: EventFamily, query: DBObject, keyMap: MongoMapFunc) = {
    // Casbah doesn't provide a way to use MongoDB's $keyf, to group based on a meta-key,
    // so we're forced to use the Java API

    val groupCmd = new BasicDBObject("ns", eventFamily.name)
    groupCmd.append("$keyf", keyMap.js)
    groupCmd.append("cond", query)
    groupCmd.append("$reduce", MongoReduceFunc.AGGREGATE.js)
    groupCmd.append("initial", MongoReduceFunc.AGGREGATE.initial)
    val cmd = new BasicDBObject("group",groupCmd)
    Json.parse(mongoDB.command(cmd).toString) \ "retval" match {
      case JsArray(s) => s
      case s: JsValue => throw new Exception("Invalid response from Mongodb: %s".format(s))
    }
  }

  def find(mongoSelector: MongoSelector) = {
    val coll = mongoDB(mongoSelector.eventFamily.name)
    coll.find(mongoSelector)
  }

  private def query(eventFamily: EventFamily, q: DBObject): MongoCursor = {
    val coll = mongoDB(eventFamily.name)
    coll.find(q)
  }

  private def cursorToEvent(cur: MongoCursor): Iterator[Event] = {
    cur.map(EventSerializer.eventSerializer.mongoReads(_))
  }
}

class FakeMongoEventStoreImpl() extends MongoEventStore with Logging {
  def save(event: Event): Unit = {
    log.info("Saving event: %s".format(event.toString))
  }

  def save(eventFamily: EventFamily, dbObject: DBObject): Unit = {
    log.info("Saving to collection %s: %s".format(eventFamily.name, dbObject))
  }
}