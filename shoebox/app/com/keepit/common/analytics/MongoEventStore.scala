package com.keepit.common.analytics

import com.keepit.common.db.ExternalId
import play.api.libs.json.Format
import com.keepit.serializer.EventSerializer
import com.mongodb.casbah.MongoConnection
import com.amazonaws.services.s3.AmazonS3
import com.mongodb.casbah.Imports._
import com.keepit.common.logging.Logging

trait MongoEventStore {
  def save(event: Event): Unit
  def save(eventFamily: EventFamily, dbObject: DBObject): Unit
}

class MongoResults {

}


class MongoEventStoreImpl(val mongoDB: MongoDB) extends MongoEventStore with Logging {
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