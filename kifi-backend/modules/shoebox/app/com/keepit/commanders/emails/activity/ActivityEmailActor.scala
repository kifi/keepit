package com.keepit.commanders.emails.activity

import java.util.concurrent.atomic.AtomicBoolean

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Regions
import com.google.inject.{ Provides, Inject, Singleton }
import com.keepit.commanders.emails.ActivityFeedEmailSender
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.User
import com.keepit.common.db.Id
import com.kifi.franz.{ SimpleSQSClient, QueueName, SQSQueue, FakeSQSQueue }
import net.codingwell.scalaguice.ScalaModule
import play.api.libs.json.{ Json, Format, Writes }
import scala.concurrent.duration._

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class ActivityEmailActor @Inject() (
    serviceDiscovery: ServiceDiscovery,
    activityEmailQueueHelper: ActivityFeedEmailQueueHelper,
    protected val airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  import ActivityEmailMessage._

  val isRunning = new AtomicBoolean(false)

  def receive: PartialFunction[Any, Unit] = {
    case QueueEmails => {
      log.info("[Queue] calling ActivityEmailSender.send()")
      if (serviceDiscovery.isLeader()) {
        activityEmailQueueHelper.addToQueue().foreach(_ => {
          log.info("[Queue] all emails queued; sending emails..")
          self ! SendEmails
        })
      } else {
        airbrake.notify("ActivityEmailSender.send() should not be called by non-leader!")
        Future.successful(Unit)
      }
    }
    case SendEmails => {
      if (isRunning.compareAndSet(false, true)) {
        activityEmailQueueHelper.processQueue().onComplete(_ => isRunning.set(false))
      } else {
        log.info("[Send] skipping; already running")
        Future.successful(Unit)
      }
    }
  }
}

class ActivityFeedEmailQueueHelper @Inject() (
    activityEmailSender: ActivityFeedEmailSender,
    queue: SQSQueue[SendActivityEmailToUserMessage],
    protected val airbrake: AirbrakeNotifier) extends Logging {
  private val queueLock = new ReactiveLock(10)

  def addToQueue(): Future[Unit] = {
    //    val usersIdsToSendTo: Set[Id[User]] = activityEmailSender.usersToSendEmailTo()
    val usersIdsToSendTo: Set[Id[User]] = Set(1, 3, 134, 243, 115, 7456).map(i => Id[User](i))

    val seqF = usersIdsToSendTo.map { userId =>
      queueLock.withLockFuture { queue.send(SendActivityEmailToUserMessage(userId)) }
    }

    Future.sequence(seqF) map (_ -> Unit)
  }

  def processQueue(): Future[Unit] = {
    def fetchFromQueue(): Future[Boolean] = {
      log.info(s"[processQueue] fetching message from queue ${queue.queue.name}")
      queue.nextWithLock(1 minute).flatMap { messageOpt =>
        messageOpt map { message =>
          try {
            val emailF = activityEmailSender.sendToUser(message.body.userId)
            emailF map { emailToSend =>
              log.info(s"[processQueue] consumed activity email to=${emailToSend.to}")
              message.consume()
            } recover {
              case e =>
                airbrake.notify(s"error sending activity email to ${message.body.userId}", e)
            } map (_ => true)
          } catch {
            case e: Exception =>
              airbrake.notify(s"error sending activity email to ${message.body.userId} before future", e)
              Future.successful(true)
          }
        } getOrElse Future.successful(false)
      }
    }

    val doneF: Future[Unit] = FutureHelpers.whilef(fetchFromQueue())(Unit)
    doneF.onFailure {
      case e => airbrake.notify(s"SQS queue(${queue.queue.name}) nextWithLock failed", e)
    }

    doneF
  }

}

case class SendActivityEmailToUserMessage(userId: Id[User])

object ActivityEmailMessage {
  object QueueEmails
  object SendEmails
}

object SendActivityEmailToUserMessage {
  import play.api.libs.json.__
  implicit val format = Format(
    __.read[Id[User]].map(SendActivityEmailToUserMessage.apply),
    new Writes[SendActivityEmailToUserMessage] { def writes(o: SendActivityEmailToUserMessage) = Json.toJson(o.userId) }
  )
}

trait ActivityEmailQueueModule extends ScalaModule {
  val queueName: String

  override def configure(): Unit = {}
}

@Singleton
case class ProdActivityEmailQueueModule() extends ActivityEmailQueueModule with Logging {
  val queueName = "prod-shoebox-activity-email"

  @Singleton
  @Provides
  def activityEmailQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[SendActivityEmailToUserMessage] = {
    val client = SimpleSQSClient(basicAWSCreds, Regions.US_WEST_1, buffered = false)
    client.formatted[SendActivityEmailToUserMessage](QueueName(queueName))
  }
}

@Singleton
case class DevActivityEmailQueueModule() extends ActivityEmailQueueModule with Logging {
  val queueName = "dev-shoebox-activity-email"

  @Singleton
  @Provides
  def activityEmailQueue(basicAWSCreds: BasicAWSCredentials): SQSQueue[SendActivityEmailToUserMessage] = {
    new FakeSQSQueue[SendActivityEmailToUserMessage] {}
  }
}
