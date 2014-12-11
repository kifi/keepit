package com.keepit.model

import com.keepit.heimdal._

import scala.concurrent.Future

class FakeUserEventLoggingRepo extends DevUserEventLoggingRepo {

  var events: Vector[UserEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): UserEvent = events.head

  override def persist(obj: UserEvent): Future[Unit] = {
    synchronized { events = events :+ obj }
    Future.successful(())
  }
}

class FakeSystemEventLoggingRepo extends DevSystemEventLoggingRepo {

  var events: Vector[SystemEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): SystemEvent = events.head

  override def persist(obj: SystemEvent): Future[Unit] = {
    synchronized { events = events :+ obj }
    Future.successful(())
  }
}

class FakeAnonymousEventLoggingRepo extends DevAnonymousEventLoggingRepo {

  var events: Vector[AnonymousEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): AnonymousEvent = events.head

  override def persist(obj: AnonymousEvent): Future[Unit] = {
    synchronized { events = events :+ obj }
    Future.successful(())
  }
}

class FakeVisitorEventLoggingRepo extends DevVisitorEventLoggingRepo {

  var events: Vector[VisitorEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): VisitorEvent = events.head

  override def persist(obj: VisitorEvent): Future[Unit] = {
    synchronized { events = events :+ obj }
    Future.successful(())
  }
}

class FakeNonUserEventLoggingRepo extends DevNonUserEventLoggingRepo {

  var events: Vector[NonUserEvent] = Vector()

  def eventCount(): Int = events.length

  def lastEvent(): NonUserEvent = events.head

  override def persist(obj: NonUserEvent): Future[Unit] = {
    synchronized { events = events :+ obj }
    Future.successful(())
  }
}
