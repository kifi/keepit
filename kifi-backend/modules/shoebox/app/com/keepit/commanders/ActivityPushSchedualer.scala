package com.keepit.commanders

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.strings._
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.eliza.{ PushNotificationExperiment, PushNotificationCategory, ElizaServiceClient }
import com.keepit.model._
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.util.Random

object PushActivities
case class PushActivity(activityPushTaskId: Id[ActivityPushTask])

class ActivityPushActor @Inject() (
    activityPusher: ActivityPusher,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  private val counter = new AtomicInteger(0)
  def receive = {
    case PushActivities =>
      if (counter.get <= 0) {
        activityPusher.getNextPushBatch() foreach { activityId =>
          self ! PushActivity(activityId)
          counter.incrementAndGet()
        }
      }
    case PushActivity(activityPushTaskId) =>
      try {
        activityPusher.pushItBaby(activityPushTaskId)
      } catch {
        case e: Exception =>
          if (counter.decrementAndGet() <= 0) {
            self ! PushActivities
          }
          airbrake.notify(s"on pushing activity $activityPushTaskId", e)
      }

  }
}

class ActivityPushSchedualer @Inject() (
    activityPushTaskRepo: ActivityPushTaskRepo,
    val scheduling: SchedulingProperties,
    actor: ActorInstance[ActivityPushActor]) extends SchedulerPlugin {
  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 21 seconds, 29 seconds, actor.ref, PushActivity, PushActivity.getClass.getSimpleName)
  }

}

class ActivityPusher @Inject() (
    activityPushTaskRepo: ActivityPushTaskRepo,
    db: Database,
    elizaServiceClient: ElizaServiceClient,
    libraryRepo: LibraryRepo,
    userPersonaRepo: UserPersonaRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    actor: ActorInstance[ActivityPushActor],
    clock: Clock) {

  def pushItBaby(activityPushTaskId: Id[ActivityPushTask]): Unit = {
    val activity = db.readOnlyMaster { implicit s =>
      activityPushTaskRepo.get(activityPushTaskId)
    }
    getMessage(activity.userId) match {
      case Some((message, pushMessageType, experimant)) =>
        elizaServiceClient.sendPushNotification(activity.userId, message, pushMessageType, experimant)
        val lastActivity = getLastActivity(activity.userId)
        db.readWrite { implicit s =>
          activityPushTaskRepo.save(activity.copy(lastPush = Some(clock.now())).withLastActivity(lastActivity))
        }
      case None =>
    }
  }

  def getMessage(userId: Id[User]): Option[(String, PushNotificationCategory, PushNotificationExperiment)] = {
    val experiment = if (Random.nextBoolean()) PushNotificationExperiment.Experiment1 else PushNotificationExperiment.Experiment2
    val res: Option[(String, PushNotificationCategory)] = db.readOnlyReplica { implicit s =>
      libraryMembershipRepo.getLatestUpdatedLibraryUserFollow(userId) map { lib =>
        val message = {
          if (experiment == PushNotificationExperiment.Experiment1) s"""Your personalized feed has been updated based on "${lib.name.abbreviate(25)}", a library you follow."""
          else s"""A library you follow has been updated: "${lib.name.abbreviate(25)}" Check out your updated feed."""
        }
        message -> PushNotificationCategory.LibraryChanged
      } orElse {
        val personas = Random.shuffle(userPersonaRepo.getPersonasForUser(userId))
        val message = {
          if (personas.isEmpty) None
          else if (personas.size == 1) {
            val msg = {
              if (experiment == PushNotificationExperiment.Experiment1) s"""Your feed has updates. See what other ${personas.head.displayNamePlural} are talking about."""
              else s"""Your feed has updates. See what other ${personas.head.displayNamePlural} are reading."""
            }
            Some(msg)
          } else {
            val msg = {
              if (experiment == PushNotificationExperiment.Experiment1) s"""Your feed has updates. See what other ${personas.head.displayNamePlural} and ${personas(1).displayNamePlural} are talking about."""
              else s"""Your feed has updates. See what other ${personas.head.displayNamePlural} and ${personas(1).displayNamePlural} are reading."""
            }
            Some(msg)
          }
        }
        message.map(m => m -> PushNotificationCategory.PersonaUpdate)
      }
    }
    res.map(r => (r._1, r._2, experiment))
  }

  def getLastActivity(userId: Id[User]): DateTime = {
    val libs = db.readOnlyReplica { implicit s =>
      libraryRepo.getAllByOwner(userId)
    }
    libs.map(lib => lib.lastKept.getOrElse(lib.updatedAt)).sorted.reverse.head
  }

  def getNextPushBatch(): Seq[Id[ActivityPushTask]] = {
    db.readOnlyReplica { implicit s =>
      val now = clock.now()
      activityPushTaskRepo.getByPushAndActivity(now.minusDays(2), now.toLocalTimeInZone(DEFAULT_DATE_TIME_ZONE), 100)
    }
  }

}
