package com.keepit.shoebox.cron

import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.social.BasicUserRepo

import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

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
import com.keepit.eliza.{ LibraryPushNotificationCategory, SimplePushNotificationCategory, PushNotificationExperiment, ElizaServiceClient }
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
        activityPusher.getNextPushBatch foreach { activityId =>
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
        val currCount = counter.decrementAndGet()
        if (currCount <= 0) {
          log.info(s"[ActivityPushActor] Would have self-called. $currCount")
        }
      }
  }
}

class ActivityPushScheduler @Inject() (
    activityPushTaskRepo: ActivityPushTaskRepo,
    val scheduling: SchedulingProperties,
    actor: ActorInstance[ActivityPushActor]) extends SchedulerPlugin {
  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 21.seconds, 29.seconds, actor.ref, PushActivities, PushActivities.getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 1.hour, 1.hours, actor.ref, CreatePushActivityEntities, CreatePushActivityEntities.getClass.getSimpleName)
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
    kifiInstallationRepo: KifiInstallationRepo,
    actor: ActorInstance[ActivityPushActor],
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {

  def updatedActivity(userId: Id[User]): Unit = {
    // Gets called when user opens app.
    // Updates last active record if it exists, updates all relevant dates and backoff
    db.readWrite { implicit session =>
      val now = clock.now
      val pushTask = activityPushTaskRepo.getByUser(userId).getOrElse {
        createActivityPushForUser(userId, now, ActivityPushTaskStates.INACTIVE)
      }
      val recentInstall = pushTask.createdAt.plusDays(1) > now
      activityPushTaskRepo.save(pushTask.copy(
        lastActiveDate = now,
        lastActiveTime = now.toLocalTime,
        nextPush = Some(if (recentInstall) now.plusDays(1) else now.plusDays(2)),
        backoff = Some(2.days)
      ))
    }

  }

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
    /*
     * activityPushTaskId is for a candidate to push, but may not require a push.
     * This can happen when the user was active after the last push.
     */
    val (activity, canNotify) = db.readOnlyMaster { implicit s =>
      val activity = activityPushTaskRepo.get(activityPushTaskId)
      val canNotify = notifyPreferenceRepo.canNotify(activity.userId, NotifyPreference.RECOS_REMINDER)
      (activity, canNotify)
    }
    if (!canNotify) {
      //todo(eishay) re-enable when user resets prefs
      db.readWrite { implicit s =>
        log.info(s"user asked not to be notified on RECOS_REMINDER, disabling his task")
        activityPushTaskRepo.save(activity.copy(state = ActivityPushTaskStates.INACTIVE))
      }
    } else if (shouldNotify(activity)) {
      pushActivity(activity)
    }
  }

  private def pushMessage(activity: ActivityPushTask, message: PushNotificationMessage, experimant: PushNotificationExperiment): Unit = {
    val res: Future[Int] = message match {
      case libMessage: LibraryPushNotificationMessage =>
        log.info(s"pushing library activity update to ${activity.userId} [$experimant]: $message")
        elizaServiceClient.sendLibraryPushNotification(activity.userId, libMessage.message, libMessage.id, libMessage.libraryUrl, experimant, LibraryPushNotificationCategory.LibraryChanged)
      case generalMessage: GeneralActivityPushNotificationMessage =>
        log.info(s"pushing general activity update to ${activity.userId} [$experimant]: $message")
        elizaServiceClient.sendGeneralPushNotification(activity.userId, generalMessage.message, experimant, SimplePushNotificationCategory.PersonaUpdate)
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
    db.readWrite { implicit s =>
      val prevActivity = activityPushTaskRepo.get(activity.id.get)
      val newBackoff = prevActivity.backoff.getOrElse(1.day).plus(1.day)
      val newActivity = prevActivity.copy(
        nextPush = Some(clock.now().plusMillis(newBackoff.toMillis.toInt)),
        backoff = Some(newBackoff),
        lastPush = Some(clock.now())
      )
      activityPushTaskRepo.save(newActivity)
    }
  }

  private def pushActivity(activity: ActivityPushTask): Unit = {
    db.readOnlyReplica { implicit s =>
      kifiInstallationRepo.lastUpdatedMobile(activity.userId)
    }.foreach { latestInstallation =>
      getMessage(activity.userId, latestInstallation) match {
        case Some((message, experimant)) =>
          pushMessage(activity, message, experimant)
        case None =>
          log.info(s"skipping push activity for user ${activity.userId}")
      }
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

  def canSendPushForLibraries(installation: KifiInstallation): Boolean = {
    installation.platform match {
      case KifiInstallationPlatform.Android =>
        installation.version.compareIt(KifiAndroidVersion("2.2.4")) >= 0
      case KifiInstallationPlatform.IPhone =>
        installation.version.compareIt(KifiIPhoneVersion("2.1.0")) >= 0
      case _ => throw new Exception(s"Don't know platform for $installation")
    }
  }

  def getMessage(userId: Id[User], installation: KifiInstallation): Option[(PushNotificationMessage, PushNotificationExperiment)] = {
    val experiment = if (Random.nextBoolean()) PushNotificationExperiment.Experiment1 else PushNotificationExperiment.Experiment2
    val libMessage = if (canSendPushForLibraries(installation)) getLibraryActivityMessage(experiment, userId) else None
    val messageOpt = libMessage orElse getPersonaActivityMessage(experiment, userId)
    messageOpt.map(message => (message, experiment))
  }

  def getLastActivity(userId: Id[User])(implicit session: RSession): DateTime = {
    val installation = kifiInstallationRepo.lastUpdatedMobile(userId).getOrElse(throw new Exception(s"should not get to this point if user $userId has no mobile installation"))
    val lastActive = libraryRepo.getAllByOwner(userId).map(lib => lib.lastKept.getOrElse(lib.updatedAt)).sorted.reverse.headOption
    lastActive match {
      case Some(time) if time.isAfter(installation.updatedAt) => time
      case _ => installation.updatedAt
    }
  }

  def createPushActivityEntities(batchSize: Int): Seq[Id[ActivityPushTask]] = {
    val allTasks = mutable.ArrayBuffer[Id[ActivityPushTask]]()
    var lastBatchSize = batchSize
    while (lastBatchSize >= batchSize) {
      val batch = createPushActivityEntitiesBatch(batchSize)
      allTasks ++= batch
      lastBatchSize = batch.size
      log.info(s"created $lastBatchSize new activity rows")
    }
    allTasks.toSeq
  }

  private def createPushActivityEntitiesBatch(batchSize: Int): Seq[Id[ActivityPushTask]] = {
    val users = db.readOnlyMaster { implicit s => //need to use master since we'll quickly gate to race condition because of replica lag
      val userIds = activityPushTaskRepo.getMobileUsersWithoutActivityPushTask(batchSize)
      val usersWithLastKeep = userRepo.getAllUsers(userIds).values map { user =>
        user -> getLastActivity(user.id.get)
      }
      usersWithLastKeep.toSeq
    }
    db.readWrite { implicit s =>
      log.info(s"[createPushActivityEntitiesBatch] creating ${users.size} tasks for users")
      users map {
        case (user, lastActive) =>
          createActivityPushForUser(user.id.get, lastActive, ActivityPushTaskStates.INACTIVE).id.get
      }
    }
  }

  private def createActivityPushForUser(userId: Id[User], lastActive: DateTime, state: State[ActivityPushTask])(implicit session: RWSession): ActivityPushTask = {
    val task = ActivityPushTask(
      userId = userId,
      lastActiveDate = lastActive,
      lastActiveTime = lastActive.toLocalTime,
      state = state,
      nextPush = None,
      backoff = None)
    activityPushTaskRepo.save(task)
  }

  def getNextPushBatch: Seq[Id[ActivityPushTask]] = {
    val ids = db.readOnlyMaster { implicit s =>
      activityPushTaskRepo.getBatchToPush(100)
    }
    log.info(s"next push batch size is ${ids.size}")
    ids
  }

  private def shouldNotify(pushTask: ActivityPushTask): Boolean = {
    true // todo, determine if client should recieve a push now
  }

}
