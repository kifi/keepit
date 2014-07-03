package com.keepit.abook.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import org.joda.time.DateTime
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.common.mail.EmailAddress
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.{RSession, RWSession}
import com.keepit.common.plugin.{SequencingActor, SchedulingProperties, SequencingPlugin}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.duration._
import scala.slick.jdbc.StaticQuery

case class EmailAccount(
  id: Option[Id[EmailAccount]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[EmailAccount] = EmailAccountStates.ACTIVE,
  address: EmailAddress,
  userId: Option[Id[User]] = None,
  verified: Boolean = false,
  seq: SequenceNumber[EmailAccount] = SequenceNumber.ZERO
) extends ModelWithState[EmailAccount] with ModelWithSeqNumber[EmailAccount] {

  def withId(id: Id[EmailAccount]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[EmailAccount]) = copy(state = state)

  if (verified) { require(userId.isDefined, "Verified EmailAccount doesn't belong to any user.") }
}

object EmailAccountStates extends States[EmailAccount]

@ImplementedBy(classOf[EmailAccountRepoImpl])
trait EmailAccountRepo extends Repo[EmailAccount] with SeqNumberFunction[EmailAccount] {
  def getByAddress(address: EmailAddress)(implicit session: RSession): Option[EmailAccount]
  def internByAddress(address: EmailAddress)(implicit session: RWSession): EmailAccount
  def getVerifiedOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]]
}

@Singleton
class EmailAccountRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock
) extends DbRepo[EmailAccount] with SeqNumberDbFunction[EmailAccount] with EmailAccountRepo {

  import db.Driver.simple._

  type RepoImpl = EmailAccountTable
  class EmailAccountTable(tag: Tag) extends RepoTable[EmailAccount](db, tag, "email_account") with SeqNumberColumn[EmailAccount] {
    def address = column[EmailAddress]("address", O.NotNull)
    def userId = column[Id[User]]("user_id", O.Nullable)
    def verified = column[Boolean]("verified", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, address, userId.?, verified, seq) <> ((EmailAccount.apply _).tupled, EmailAccount.unapply _)
  }

  def table(tag: Tag) = new EmailAccountTable(tag)
  initTable()

  private val sequence = db.getSequence[EmailAccount]("email_account_sequence")

  override def save(emailAccount: EmailAccount)(implicit session: RWSession): EmailAccount = {
    val toSave = emailAccount.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def deleteCache(emailAccount: EmailAccount)(implicit session: RSession): Unit = {}
  override def invalidateCache(emailAccount: EmailAccount)(implicit session: RSession): Unit = {}

  def getByAddress(address: EmailAddress)(implicit session: RSession): Option[EmailAccount] = {
    (for(row <- rows if row.address === address) yield row).firstOption
  }

  def internByAddress(address: EmailAddress)(implicit session: RWSession): EmailAccount = {
    getByAddress(address) match {
      case None => save(EmailAccount(address = address))
      case Some(emailAccount) => emailAccount
    }
  }

  def getVerifiedOwner(address: EmailAddress)(implicit session: RSession): Option[Id[User]] = {
    getByAddress(address).filter(_.verified).flatMap(_.userId)
  }

  override def assignSequenceNumbers(limit: Int = 20)(implicit session: RWSession): Int = {
    assignSequenceNumbers(sequence, "email_account", limit)
  }

  override def minDeferredSequenceNumber()(implicit session: RSession): Option[Long] = {
    import StaticQuery.interpolation
    sql"""select min(seq) from email_account where seq < 0""".as[Option[Long]].first
  }
}

trait EmailAccountSequencingPlugin extends SequencingPlugin

class EmailAccountSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[EmailAccountSequencingActor],
  override val scheduling: SchedulingProperties
  ) extends EmailAccountSequencingPlugin {

  override val interval: FiniteDuration = 20 seconds
}

@Singleton
class EmailAccountSequenceNumberAssigner @Inject() (db: Database, repo: EmailAccountRepo, airbrake: AirbrakeNotifier)
  extends DbSequenceAssigner[EmailAccount](db, repo, airbrake)

class EmailAccountSequencingActor @Inject() (
  assigner: EmailAccountSequenceNumberAssigner,
  airbrake: AirbrakeNotifier
) extends SequencingActor(assigner, airbrake)
