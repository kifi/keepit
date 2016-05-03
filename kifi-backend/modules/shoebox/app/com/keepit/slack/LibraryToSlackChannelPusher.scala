package com.keepit.slack

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time.{ Clock, DEFAULT_DATE_TIME_ZONE }
import com.keepit.common.util.{ SetHelpers, Debouncing }
import com.keepit.model._
import com.keepit.slack.models._
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@ImplementedBy(classOf[LibraryToSlackChannelPusherImpl])
trait LibraryToSlackChannelPusher {
  def schedule(libIds: Set[Id[Library]]): Unit
}

@Singleton
class LibraryToSlackChannelPusherImpl @Inject() (
  db: Database,
  libToChannelRepo: LibraryToSlackChannelRepo,
  clock: Clock,
  pushingActor: ActorInstance[SlackPushingActor],
  implicit val executionContext: ExecutionContext)
    extends LibraryToSlackChannelPusher with Logging {

  val buf = new Debouncing.Buffer[Set[Id[Library]]]
  def schedule(libIds: Set[Id[Library]]): Unit = {
    buf.debounce("schedule", 2 seconds)(libIds) { allLibs =>
      db.readWrite { implicit session =>
        val now = clock.now
        SetHelpers.unions(allLibs).foreach { libId => pushLibraryAtLatest(libId, now) }
      }
    }
    pushingActor.ref ! IfYouCouldJustGoAhead
  }

  def pushLibraryAtLatest(libId: Id[Library], when: DateTime)(implicit session: RWSession): Unit = {
    libToChannelRepo.getActiveByLibrary(libId).filter(_.status == SlackIntegrationStatus.On).foreach { lts =>
      val updatedLts = lts.withNextPushAtLatest(when)
      if (updatedLts != lts) libToChannelRepo.save(updatedLts)
    }
  }
}
