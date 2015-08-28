package com.keepit.shoebox.cron

import com.keepit.commanders.{ PathCommander, LibraryImageCommander, ProcessedImageSize, KifiInstallationCommander }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.store.S3ImageStore
import com.keepit.notify.NotificationEventSender
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.NewKeepActivity
import com.keepit.social.BasicUser
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db._
import com.keepit.common.concurrent.PimpMyFuture._
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
case class PersonaActivityPushNotificationMessage(message: String) extends PushNotificationMessage
case class LibraryPushNotificationMessage(message: String, lib: Library, owner: BasicUser, newKeep: Keep, libraryUrl: String, libImageOpt: Option[LibraryImage]) extends PushNotificationMessage

object PushActivities
object CreatePushActivityEntities
case class PushActivity(activityPushTaskId: Id[ActivityPushTask])

class ActivityPushActor @Inject() (
    activityPusher: ActivityPusher,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case CreatePushActivityEntities =>
      activityPusher.createPushActivityEntities(100)
    case PushActivities =>
      activityPusher.getNextPushBatch.foreach { activityId =>
        self ! PushActivity(activityId)
      }
    case PushActivity(activityPushTaskId) =>
      try {
        activityPusher.pushItBaby(activityPushTaskId)
      } catch {
        case e: Exception =>
          airbrake.notify(s"[ActivityPushActor] on pushing activity $activityPushTaskId", e)
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
    kifiInstallationCommander: KifiInstallationCommander,
    libraryImageCommander: LibraryImageCommander,
    libPathCommander: PathCommander,
    s3ImageStore: S3ImageStore,
    notificationEventSender: NotificationEventSender,
    actor: ActorInstance[ActivityPushActor],
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext,
    clock: Clock) extends Logging {

  def updatedActivity(userId: Id[User]): Unit = {
    // Gets called when user opens app.
    // Updates last active record if it exists, updates all relevant dates and backoff
    db.readWrite { implicit session =>
      val now = clock.now
      val pushTask = activityPushTaskRepo.getByUser(userId).getOrElse {
        createActivityPushForUser(userId, now)
      }
      val recentInstall = pushTask.createdAt.plusDays(1) > now
      activityPushTaskRepo.save(pushTask.copy(
        state = ActivityPushTaskStates.ACTIVE,
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

  def forcePersonaActivityForUser(userId: Id[User]): Unit = {
    val task = db.readOnlyMaster { implicit s =>
      activityPushTaskRepo.getByUser(userId)
    }
    PushNotificationExperiment.All.foreach { experiment =>
      getPersonaActivityMessage(experiment, userId) foreach { message =>
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
        activityPushTaskRepo.save(activity.copy(state = ActivityPushTaskStates.OPTED_OUT))
      }
    } else {
      pushActivity(activity)
    }
  }

  private def pushMessage(activity: ActivityPushTask, message: PushNotificationMessage, experimant: PushNotificationExperiment): Unit = {
    val res: Future[Int] = message match {
      case libMessage: LibraryPushNotificationMessage =>
        log.info(s"pushing library activity update to ${activity.userId} [$experimant]: $message")
        val devices = elizaServiceClient.sendGlobalNotification( //no need for push
          userIds = Set(activity.userId),
          title = s"New Keep in ${libMessage.lib.name}",
          body = s"${libMessage.owner.firstName} has just kept ${libMessage.newKeep.title.getOrElse("a new item")}",
          linkText = "Go to Library",
          linkUrl = libMessage.libraryUrl,
          imageUrl = s3ImageStore.avatarUrlByUser(libMessage.owner),
          sticky = false,
          category = NotificationCategory.User.NEW_KEEP,
          extra = Some(Json.obj(
            "keeper" -> libMessage.owner,
            "library" -> Json.toJson(LibraryNotificationInfoBuilder.fromLibraryAndOwner(libMessage.lib, libMessage.libImageOpt, libMessage.owner)),
            "keep" -> Json.obj(
              "id" -> libMessage.newKeep.externalId,
              "url" -> libMessage.newKeep.url
            )
          ))
        ) map { _ =>
            elizaServiceClient.sendLibraryPushNotification(activity.userId, libMessage.message, libMessage.lib.id.get, libMessage.libraryUrl, experimant, LibraryPushNotificationCategory.LibraryChanged, activity.state == ActivityPushTaskStates.NO_DEVICES)
          }
        val owner = db.readOnlyReplica { implicit session =>
          userRepo.get(libMessage.owner.externalId)
        }
        notificationEventSender.send(NewKeepActivity.build(
          Recipient(activity.userId),
          currentDateTime,
          owner,
          libMessage.newKeep,
          libMessage.lib))
        devices.flatten
      case personaMessage: PersonaActivityPushNotificationMessage =>
        log.info(s"pushing general persona activity update to ${activity.userId} [$experimant]: $message")
        elizaServiceClient.sendGeneralPushNotification(activity.userId, personaMessage.message, experimant, SimplePushNotificationCategory.PersonaUpdate, activity.state == ActivityPushTaskStates.NO_DEVICES)
    }
    res map { deviceCount =>
      log.info(s"push successful to $deviceCount devices")
      if (deviceCount <= 0) {
        db.readWrite { implicit s =>
          //there may be some race conditions with the four lines ahead, we can live with that.
          log.info(s"disable activity push task $activity until user register with at least one device")
          val prevActivity = activityPushTaskRepo.get(activity.id.get)
          val newBackoff = prevActivity.backoff.getOrElse(7.day).plus(7.day)
          val newActivity = prevActivity.copy(
            state = ActivityPushTaskStates.NO_DEVICES,
            nextPush = Some(clock.now().plusMillis(newBackoff.toMillis.toInt)),
            backoff = Some(newBackoff),
            lastPush = Some(clock.now())
          )
          activityPushTaskRepo.save(newActivity)
        }
      }
    }
    db.readWrite { implicit s =>
      val prevActivity = activityPushTaskRepo.get(activity.id.get)
      val newBackoff = prevActivity.backoff.getOrElse(1.day).plus(1.day)
      val newActivity = prevActivity.copy(
        state = ActivityPushTaskStates.ACTIVE,
        nextPush = Some(clock.now().plusMillis(newBackoff.toMillis.toInt)),
        backoff = Some(newBackoff),
        lastPush = Some(clock.now())
      )
      activityPushTaskRepo.save(newActivity)
    }
  }

  private def pushActivity(activity: ActivityPushTask): Unit = {
    getMessage(activity.userId) match {
      case Some((message, experiment)) =>
        pushMessage(activity, message, experiment)
      case None =>
      // If there's no activity message, we're not sending anything for now.
    }

  }

  private def getLibraryActivityMessage(experiment: PushNotificationExperiment, userId: Id[User]): Option[LibraryPushNotificationMessage] = {
    db.readOnlyReplica { implicit s =>
      libraryMembershipRepo.getLatestUpdatedLibraryUserFollow(userId) flatMap { lib =>
        val message = {
          if (experiment == PushNotificationExperiment.Experiment1) s"""New keeps in "${lib.name.abbreviate(25)}""""
          else s""""${lib.name.abbreviate(25)}" library has updates"""
        }
        val owner = basicUserRepo.load(lib.ownerId)
        val libraryUrl = "https://www.kifi.com" + libPathCommander.getPathForLibraryUrlEncoded(lib)
        keepRepo.getByLibrary(lib.id.get, 0, 1) match {
          case keeps if keeps.nonEmpty =>
            val libImageOpt = libraryImageCommander.getBestImageForLibrary(lib.id.get, ProcessedImageSize.Medium.idealSize)
            Some(LibraryPushNotificationMessage(message, lib, owner, keeps.head, libraryUrl, libImageOpt))
          case _ => None
        }
      }
    }
  }

  private def getPersonaActivityMessage(experiment: PushNotificationExperiment, userId: Id[User]): Option[PersonaActivityPushNotificationMessage] = {
    db.readOnlyReplica { implicit s =>
      val personas = Random.shuffle(userPersonaRepo.getPersonasForUser(userId))
      val message = {
        if (personas.isEmpty) {
          None
        } else if (personas.size == 1) {
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
      message.map(m => PersonaActivityPushNotificationMessage(m))
    }
  }

  private def getMessage(userId: Id[User]): Option[(PushNotificationMessage, PushNotificationExperiment)] = {
    val canSendLibPush = kifiInstallationCommander.isMobileVersionEqualOrGreaterThen(userId, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0"))
    val experiment = if (Random.nextBoolean()) PushNotificationExperiment.Experiment1 else PushNotificationExperiment.Experiment2
    //val libMessage = if (canSendLibPush) getLibraryActivityMessage(experiment, userId) else None
    // Removed for now because users can opt in to library update pushes, and this is confusing otherwise. More thought needed?
    val message = getPersonaActivityMessage(experiment, userId)
    message.map(m => m -> experiment)
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
      activityPushTaskRepo.getMobileUsersWithoutActivityPushTask(batchSize)
    }
    db.readWrite { implicit s =>
      log.info(s"[createPushActivityEntitiesBatch] creating ${users.size} tasks for users")
      users map {
        case user =>
          val lastActive = kifiInstallationCommander.lastMobileAppStartTime(user)
          createActivityPushForUser(user, lastActive).id.get
      }
    }
  }

  private def createActivityPushForUser(userId: Id[User], lastActive: DateTime)(implicit session: RWSession): ActivityPushTask = {
    val task = ActivityPushTask(
      userId = userId,
      lastActiveDate = lastActive,
      lastActiveTime = lastActive.toLocalTime,
      state = ActivityPushTaskStates.ACTIVE,
      nextPush = None,
      backoff = None)
    activityPushTaskRepo.save(task)
  }

  def getNextPushBatch: Seq[Id[ActivityPushTask]] = {
    val ids = db.readOnlyMaster { implicit s =>
      val tasks = activityPushTaskRepo.getBatchToPush(100)
      if (tasks.isEmpty) { //adding a low priority queue that would nibble on the tasks we think did not register on push notification yet
        val dormant = activityPushTaskRepo.getBatchNoDevicesToPush(100)
        log.info(s"loading ${dormant.size} dorment users")
        dormant
      } else tasks
    }
    log.info(s"next push batch size is ${ids.size}")
    ids
  }

}
