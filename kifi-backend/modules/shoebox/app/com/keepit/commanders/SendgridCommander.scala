package com.keepit.commanders

class SendgridCommander {

  def processNewEvents(events: Seq[SendgridEvent]): Unit = {
    val persisted = persist(events)
    sendToHeimdal(persisted)
  }

  private def persist(events: Seq[SendgridEvent]): Seq[SendgridEvent] = {
    events
  }

  private def sendToHeimdal(events: Seq[SendgridEvent]): Unit = {

  }

}
