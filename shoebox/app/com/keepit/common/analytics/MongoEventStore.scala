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
import com.mongodb.casbah.map_reduce.MapReduceStandardOutput
import com.mongodb.DBCursor
import com.mongodb.DBCollection


case class MongoSelector(eventFamily: EventFamily) {
  private var q = eventFamily.name match {
    case "" => MongoDBObject() // generic family
    case name => MongoDBObject("eventFamily" -> name)
  }

  def ofParentEvent(eventId: ExternalId[Event]): this.type = {
    q = q ++ ("metaData.prevEvents" $in eventId.id)
    this
  }
  def withId(externalId: ExternalId[Event]): this.type = {
    q = q ++ ("id" -> externalId.id)
    this
  }
  def withUser(externalId: ExternalId[User]): this.type = {
    q = q ++ ("metaData.userId" -> externalId.id)
    this
  }
  def withEventName(name: String): this.type = {
    q = q ++ ("metaData.eventName" -> name)
    this
  }
  def withEventNameIn(names: String*): this.type = {
    q = q ++ ("metaData.eventName" $in names)
    this
  }
  def withMetaData(field: String): this.type = {
    q = q ++ (s"metaData.metaData.$field" $exists true)
    this
  }
  def withMetaData(field: String, value: Boolean): this.type = {
    q = q ++ (s"metaData.metaData.$field" -> value)
    this
  }
  def withMetaData(field: String, value: String): this.type = {
    q = q ++ (s"metaData.metaData.$field" -> value)
    this
  }
  def withMetaData[T](field: String, value: T)(implicit n: Numeric[T]): this.type = {
    q = q ++ (s"metaData.metaData.$field" -> n.toDouble(value))
    this
  }
  def withDateRange(startDate: DateTime, endDate: DateTime): this.type = {
    q = q ++ ("createdAt" $gte startDate $lte endDate)
    this
  }
  def withMinDate(startDate: DateTime): this.type = {
    q = q ++ ("createdAt" $gte startDate)
    this
  }
  def withMaxDate(endDate: DateTime): this.type = {
    q = q ++ ("createdAt" $lte endDate)
    this
  }

  def build = q
  override def toString = q.toString
}


trait MongoEventStore {
  def save(event: Event): Event
  def countGroup(eventFamily: EventFamily, query: DBObject, keyMap: MongoKeyMapFunc): Seq[JsObject]
  def mapReduce(collection: String, map: MongoMapFunc, reduce: MongoReduceFunc, outputCollection: Option[String], query: Option[DBObject], finalize: Option[MongoReduceFunc]): Iterator[DBObject]
  def find(mongoSelector: MongoSelector): Iterator[DBObject]
  def find(collection: String, mongoSelector: MongoSelector): Iterator[DBObject]
}

class MongoEventStoreImpl(val mongoDB: MongoDB) extends MongoEventStore with Logging {
  RegisterJodaTimeConversionHelpers()
  implicit def MongoSelectorToDBObject(m: MongoSelector): DBObject = m.build

  def save(event: Event): Event = {
    saveEventObject(event.metaData.eventFamily, EventSerializer.eventSerializer.mongoWrites(event))
    event
  }

  private def saveEventObject(eventFamily: EventFamily, dbObject: DBObject): Unit = {
    val coll = mongoDB(eventFamily.collection)
    val result = coll.insert(dbObject)
  }

  def countGroup(eventFamily: EventFamily, query: DBObject, keyMap: MongoKeyMapFunc): Seq[JsObject] = {
    // Casbah doesn't provide a way to use MongoDB's $keyf to group based on a meta-key,
    // so we're forced to use the (ugly) Java API.
    // https://jira.mongodb.org/browse/JAVA-99

    val groupCmd = new BasicDBObject("ns", eventFamily.collection)
    groupCmd.append("$keyf", keyMap.js)
    groupCmd.append("cond", query)
    groupCmd.append("$reduce", MongoReduceFunc.KEY_AGGREGATE.js)
    groupCmd.append("initial", DBObject("count" -> 0))
    val cmd = new BasicDBObject("group",groupCmd)
    val result = mongoDB.command(cmd)
    Json.parse(result.toString) \ "retval" match {
      case JsArray(s) => s map (_.as[JsObject])
      case s: JsValue => throw new Exception("Invalid response from Mongodb: %s\nResponse: %s".format(s, result))
    }
  }

  def mapReduce(collection: String, map: MongoMapFunc, reduce: MongoReduceFunc, outputCollection: Option[String], query: Option[DBObject], finalize: Option[MongoReduceFunc]): Iterator[DBObject] = {
    val coll = mongoDB(collection)
    val output = if(outputCollection.isDefined) {
      MapReduceStandardOutput(outputCollection.get)
    } else {
      MapReduceInlineOutput
    }
    coll.mapReduce(map.js, reduce.js, output, query).cursor
  }

  def find(mongoSelector: MongoSelector): Iterator[DBObject] = {
    val coll = mongoDB(mongoSelector.eventFamily.collection)
    coll.find(mongoSelector)
  }

  def find(collection: String, mongoSelector: MongoSelector): Iterator[DBObject] = {
    val coll = mongoDB(collection)
    coll.find(mongoSelector)
  }

  private def cursorToEventOpt(cur: MongoCursor): Iterator[Option[Event]] = {
    // Since we may have schema-breaking records, fail gracefully when events fail to deserialize from Mongo, and log
    // Leaving as an Option to keep the cursor lazy
    cur.map( result =>
      try {
        Some(dbObjectToEvent(result))
      } catch {
        case ex: Throwable =>
          log.warn("Failed to deserialize Event from MongoDB: %s".format(ex.getMessage))
          None
      }
    )
  }

  private def dbObjectToEvent(o: DBObject): Event = EventSerializer.eventSerializer.mongoReads(o).get
}

class FakeMongoEventStoreImpl() extends MongoEventStore with Logging {
  def save(event: Event): Event = {
    log.info("Saving event: %s".format(event.toString))
    event
  }

  def countGroup(eventFamily: EventFamily, query: DBObject, keyMap: MongoKeyMapFunc): Seq[JsObject] = {
    Seq[JsObject]()
  }

  def mapReduce(collection: String, map: MongoMapFunc, reduce: MongoReduceFunc, outputCollection: Option[String], query: Option[DBObject], finalize: Option[MongoReduceFunc]): Iterator[DBObject] = {
    Seq[DBObject]().toIterator
  }

  def find(mongoSelector: MongoSelector): Iterator[DBObject] = {
    Seq[DBObject]().toIterator
  }

  def find(collection: String, mongoSelector: MongoSelector): Iterator[DBObject] = {
    Seq[DBObject]().toIterator
  }
}

