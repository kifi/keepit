package com.keepit.common.analytics

import com.keepit.common.db.ExternalId
import play.api.libs.json.Format
import com.keepit.serializer.EventSerializer
import com.mongodb.casbah.MongoConnection
import com.amazonaws.services.s3.AmazonS3
import com.mongodb.casbah.Imports._
import com.keepit.common.logging.Logging

trait MongoEventStore {
  def save(collectionName: EventCollection, dbObject: DBObject): Unit
}

sealed case class EventCollection(value: String)
object Collections {
  val USER_EVENT = EventCollection("user")
  val SERVER_EVENT = EventCollection("server")
}

class MongoEventStoreImpl(val mongoDB: MongoDB) extends MongoEventStore with Logging {
  def save(collectionName: EventCollection, dbObject: DBObject): Unit = {
    val coll = mongoDB(collectionName.value)
    val result = coll.insert(dbObject)
    log.info(result)
  }
}