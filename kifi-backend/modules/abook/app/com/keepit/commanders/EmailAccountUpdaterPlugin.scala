package com.keepit.commanders

import com.google.inject.{Singleton, Inject}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.abook.model.{EmailAccount, EmailAccountUpdateSequenceNumberRepo, EmailAccountRepo}
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import com.keepit.common.logging.Logging
import com.keepit.model.EmailAccountUpdate
import com.keepit.common.db.slick.Database
import scala.util.{Failure, Success}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.abook.EContactRepo

sealed trait EmailAccountUpdaterActorMessage
object EmailAccountUpdaterActorMessage {
  case class FetchEmailUpdates(fetchSize: Int) extends EmailAccountUpdaterActorMessage
  case class ProcessEmailUpdates(updates: Seq[EmailAccountUpdate], fetchSize: Int) extends EmailAccountUpdaterActorMessage
}

class EmailAccountUpdaterActor @Inject() (
  shoebox: ShoeboxServiceClient,
  emailAccountRepo: EmailAccountRepo,
  contactRepo: EContactRepo,
  sequenceNumberRepo: EmailAccountUpdateSequenceNumberRepo,
  db: Database,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {
  import EmailAccountUpdaterActorMessage._

  private var updating = false

  def receive = {

    case FetchEmailUpdates(fetchSize: Int) => if (!updating) {
      val seqNum = db.readOnly { implicit session => sequenceNumberRepo.get() }
      shoebox.getEmailAccountUpdates(seqNum, fetchSize).onComplete {
        case Success(updates) => {
          log.info(s"${updates.length} EmailAccountUpdates were successfully fetched.")
          self ! ProcessEmailUpdates(updates, fetchSize)
        }
        case Failure(_) => {
          log.error(s"Failed to fetch EmailAccountUpdates.")
          updating = false
        }
      }
    }

    case ProcessEmailUpdates(updates, fetchSize) => try {
      if (updates.nonEmpty) db.readWrite(attempts = 2) { implicit session =>
        updates.sortBy(_.seq).foreach {

          case update if !update.deleted => {
            val emailAccount = emailAccountRepo.getByAddress(update.emailAddress) getOrElse EmailAccount(address = update.emailAddress)
            val savedAccount = emailAccountRepo.save(emailAccount.copy(userId = Some(update.userId), verified = update.verified))
            if (savedAccount.verified && (!emailAccount.verified || savedAccount.userId != emailAccount.userId)) {
              contactRepo.updateOwnership(savedAccount.address, savedAccount.userId)
            }
          }

          case deletion => {
            emailAccountRepo.getByAddress(deletion.emailAddress).foreach { emailAccount =>
              if (emailAccount.userId == Some(deletion.userId)) {
                emailAccountRepo.save(emailAccount.copy(userId = None, verified = false))
                if (emailAccount.verified) { contactRepo.updateOwnership(emailAccount.address, None) }
              }
            }
          }
        }
        sequenceNumberRepo.set(updates.map(_.seq).max)
      }

      log.info(s"${updates.length} EmailAccountUpdates were successfully ingested.")

      if (updates.length < fetchSize) { updating = false }
      else { self ! FetchEmailUpdates(fetchSize) }
    }

    case m => throw new UnsupportedActorMessage(m)
  }
}

@Singleton
class EmailAccountUpdaterPlugin @Inject() (
  actor: ActorInstance[EmailAccountUpdaterActor],
  val scheduling: SchedulingProperties
) extends SchedulerPlugin {
  import EmailAccountUpdaterActorMessage._

  override def onStart() {
    scheduleTaskOnLeader(actor.system, 2 minutes, 5 minutes, actor.ref, FetchEmailUpdates(100))
  }
}
