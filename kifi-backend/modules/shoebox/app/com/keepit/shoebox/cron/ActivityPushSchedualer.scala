package com.keepit.shoebox.cron

import com.keepit.common.social.BasicUserRepo

import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Future, Promise }

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.eliza.{ PushNotificationExperiment, ElizaServiceClient, PushNotificationCategory }
import com.keepit.model._
import org.joda.time.DateTime

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

trait PushNotificationMessage
case class GeneralActivityPushNotificationMessage(message: String) extends PushNotificationMessage
case class LibraryPushNotificationMessage(message: String, id: Id[Library], libraryUrl: String) extends PushNotificationMessage

object PushActivities
object CreatePushActivityEntities
case class PushActivity(activityPushTaskId: Id[ActivityPushTask])

class ActivityPushActor @Inject() (
    activityPusher: ActivityPusher,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  private val counter = new AtomicInteger(0)
  def receive = {
    case CreatePushActivityEntities =>
      activityPusher.createPushActivityEntities(100)
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
          airbrake.notify(s"on pushing activity $activityPushTaskId", e)
      } finally {
        if (counter.decrementAndGet() <= 0) {
          self ! PushActivities
        }
      }
  }
}

class ActivityPushSchedualer @Inject() (
    activityPushTaskRepo: ActivityPushTaskRepo,
    val scheduling: SchedulingProperties,
    actor: ActorInstance[ActivityPushActor]) extends SchedulerPlugin {
  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 21 seconds, 29 seconds, actor.ref, PushActivities, PushActivities.getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 1 hour, 1 hours, actor.ref, CreatePushActivityEntities, CreatePushActivityEntities.getClass.getSimpleName)
  }
  def createPushActivityEntities() = actor.ref ! CreatePushActivityEntities
}

class ActivityPusher @Inject() (
    activityPushTaskRepo: ActivityPushTaskRepo,
    db: Database,
    elizaServiceClient: ElizaServiceClient,
    libraryRepo: LibraryRepo,
    keepRepo: KeepRepo,
    userRepo: UserRepo,
    basicUserRepo: BasicUserRepo,
    notifyPreferenceRepo: UserNotifyPreferenceRepo,
    userPersonaRepo: UserPersonaRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    actor: ActorInstance[ActivityPushActor],
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {

  def forcePushLibraryActivityForUser(userId: Id[User]): Unit = {
    val task = db.readOnlyMaster { implicit s =>
      activityPushTaskRepo.getByUser(userId)
    }
    PushNotificationExperiment.All.foreach { experiment =>
      getLibraryActivityMessage(experiment, userId) foreach { message =>
        pushMessage(task.get, message, experiment)
      }
    }
  }

  def pushItBaby(activityPushTaskId: Id[ActivityPushTask]): Unit = {
    val (activity, canNotify) = db.readOnlyMaster { implicit s =>
      val activity = activityPushTaskRepo.get(activityPushTaskId)
      val canNotify = notifyPreferenceRepo.canNotify(activity.userId, NotifyPreference.RECOS_REMINDER)
      (activity, canNotify)
    }
    if (canNotify) {
      pushActivity(activity)
    } else {
      //todo(eishay) re-enable when user resets prefs
      db.readWrite { implicit s =>
        log.info(s"user asked not to be notified on RECOS_REMINDER, disabling his task")
        activityPushTaskRepo.save(activity.copy(state = ActivityPushTaskStates.INACTIVE))
      }
    }
  }

  private def pushMessage(activity: ActivityPushTask, message: PushNotificationMessage, experimant: PushNotificationExperiment): Unit = {
    val res: Future[Int] = message match {
      case libMessage: LibraryPushNotificationMessage =>
        log.info(s"pushing library activity update to ${activity.userId} [$experimant]: $message")
        elizaServiceClient.sendLibraryPushNotification(activity.userId, libMessage.message, libMessage.id, libMessage.libraryUrl, experimant)
      case generalMessage: GeneralActivityPushNotificationMessage =>
        log.info(s"pushing general activity update to ${activity.userId} [$experimant]: $message")
        elizaServiceClient.sendGeneralPushNotification(activity.userId, generalMessage.message, experimant)
    }
    res map { deviceCount =>
      log.info(s"push successful to $deviceCount devices")
      if (deviceCount <= 0) {
        db.readWrite { implicit s =>
          //there may be some race conditions with the four lines ahead, we can live with that.
          log.info(s"disable activity push task $activity until user register with at least one device")
          activityPushTaskRepo.save(activityPushTaskRepo.get(activity.id.get).copy(state = ActivityPushTaskStates.NO_DEVICES))
        }
      }
    }
    val lastActivity = getLastActivity(activity.userId)
    db.readWrite { implicit s =>
      activityPushTaskRepo.save(activityPushTaskRepo.get(activity.id.get).copy(lastPush = Some(clock.now())).withLastActivity(lastActivity))
    }
  }

  private def pushActivity(activity: ActivityPushTask): Unit = {
    getMessage(activity.userId) match {
      case Some((message, experimant)) =>
        pushMessage(activity, message, experimant)
      case None =>
        log.info(s"skipping push activity for user ${activity.userId}")
    }
  }

  private def getLibraryActivityMessage(experiment: PushNotificationExperiment, userId: Id[User]): Option[LibraryPushNotificationMessage] = {
    db.readOnlyReplica { implicit s =>
      libraryMembershipRepo.getLatestUpdatedLibraryUserFollow(userId) map { lib =>
        val message = {
          if (experiment == PushNotificationExperiment.Experiment1) s"""New keeps in "${lib.name.abbreviate(25)}""""
          else s""""${lib.name.abbreviate(25)}" library has updates"""
        }
        val libraryUrl = "https://www.kifi.com" + Library.formatLibraryPathUrlEncoded(basicUserRepo.load(lib.ownerId).username, lib.slug)
        LibraryPushNotificationMessage(message, lib.id.get, libraryUrl)
      }
    }
  }

  private def getPersonaActivityMessage(experiment: PushNotificationExperiment, userId: Id[User]): Option[GeneralActivityPushNotificationMessage] = {
    db.readOnlyReplica { implicit s =>
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
      message.map(m => GeneralActivityPushNotificationMessage(m))
    }
  }

  def getMessage(userId: Id[User]): Option[(PushNotificationMessage, PushNotificationExperiment)] = {
    val experiment = if (Random.nextBoolean()) PushNotificationExperiment.Experiment1 else PushNotificationExperiment.Experiment2
    val res: Option[PushNotificationMessage] = getLibraryActivityMessage(experiment, userId) orElse getPersonaActivityMessage(experiment, userId)
    res.map(message => (message, experiment))
  }

  def getLastActivity(userId: Id[User]): DateTime = {
    val libs = db.readOnlyReplica { implicit s =>
      libraryRepo.getAllByOwner(userId)
    }
    libs.map(lib => lib.lastKept.getOrElse(lib.updatedAt)).sorted.reverse.head
  }

  def createPushActivityEntities(batchSize: Int): Seq[Id[ActivityPushTask]] = {
    val allTasks = mutable.ArrayBuffer[Id[ActivityPushTask]]()
    var lastBatchSize = batchSize
    while (lastBatchSize >= batchSize) {
      val batch = createPushActivityEntitiesBatch(batchSize)
      allTasks ++= batch
      lastBatchSize = batch.size
      log.info(s"created ${lastBatchSize} new activity rows")
    }
    allTasks.toSeq
  }

  private def createPushActivityEntitiesBatch(batchSize: Int): Seq[Id[ActivityPushTask]] = {
    val users = db.readOnlyMaster { implicit s => //need to use master since we'll quickly gate to race condition because of replica lag
      val userIds = activityPushTaskRepo.getUsersWithoutActivityPushTask(batchSize)
      val usersWithLastKeep = userRepo.getAllUsers(userIds).values map { user =>
        user -> keepRepo.latestKeep(user.id.get)
      }
      usersWithLastKeep.toSeq
    }
    db.readWrite { implicit s =>
      log.info(s"creating ${users.size} tasks for users")
      users map {
        case (user, lastKeep) =>
          val lastActiveDate = lastKeep.getOrElse(user.createdAt)
          val task = ActivityPushTask(userId = user.id.get, lastActiveDate = lastActiveDate, lastActiveTime = lastActiveDate.toLocalTime, state = ActivityPushTaskStates.INACTIVE)
          activityPushTaskRepo.save(task).id.get
      }
    }
  }

  def getNextPushBatch(): Seq[Id[ActivityPushTask]] = {
    val ids = db.readOnlyMaster { implicit s =>
      val now = clock.now()
      activityPushTaskRepo.getByPushAndActivity(now.minusHours(12), now.toLocalTimeInZone(DEFAULT_DATE_TIME_ZONE), 100)
    }
    log.info(s"next push batch size is ${ids.size}")
    ids
  }

}
