package com.keepit.common.analytics

import com.google.inject.Inject

class EventRepo @Inject() (eventStore: EventStore, mongoEventStore: MongoEventStore) {
  def persistToS3(event: Event): Event = {
    eventStore += (event.externalId -> event)
    event
  }
  def persistToMongo(event: Event): Event = mongoEventStore.save(event)
  def persist(event: Event): Event = {
    persistToS3(event)
    persistToMongo(event)
  }
}
