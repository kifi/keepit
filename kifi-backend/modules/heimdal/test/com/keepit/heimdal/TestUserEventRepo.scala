package com.keepit.heimdal

class TestUserEventLoggingRepo extends DevUserEventLoggingRepo {

  var events: Vector[UserEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): UserEvent = events.head

  override def persist(obj: UserEvent) : Unit = synchronized { events = events :+ obj }
}

class TestSystemEventLoggingRepo extends DevSystemEventLoggingRepo {

  var events: Vector[SystemEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): SystemEvent = events.head

  override def persist(obj: SystemEvent) : Unit = synchronized { events = events :+ obj }
}

class TestAnonymousEventLoggingRepo extends DevAnonymousEventLoggingRepo {

  var events: Vector[AnonymousEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): AnonymousEvent = events.head

  override def persist(obj: AnonymousEvent) : Unit = synchronized { events = events :+ obj }
}