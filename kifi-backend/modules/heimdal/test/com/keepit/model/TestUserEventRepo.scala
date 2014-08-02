package com.keepit.model

import com.keepit.heimdal._

class FakeUserEventLoggingRepo extends DevUserEventLoggingRepo {

  var events: Vector[UserEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): UserEvent = events.head

  override def persist(obj: UserEvent): Unit = synchronized { events = events :+ obj }
}

class FakeSystemEventLoggingRepo extends DevSystemEventLoggingRepo {

  var events: Vector[SystemEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): SystemEvent = events.head

  override def persist(obj: SystemEvent): Unit = synchronized { events = events :+ obj }
}

class FakeAnonymousEventLoggingRepo extends DevAnonymousEventLoggingRepo {

  var events: Vector[AnonymousEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): AnonymousEvent = events.head

  override def persist(obj: AnonymousEvent): Unit = synchronized { events = events :+ obj }
}

class FakeNonUserEventLoggingRepo extends DevNonUserEventLoggingRepo {

  var events: Vector[NonUserEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): NonUserEvent = events.head

  override def persist(obj: NonUserEvent): Unit = synchronized { events = events :+ obj }
}
