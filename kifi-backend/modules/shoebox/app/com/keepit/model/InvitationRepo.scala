package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.actor.ActorInstance

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ DbSequenceAssigner, Id, State }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.plugin.{ SequencingActor, SchedulingProperties, SequencingPlugin }
import com.keepit.common.time.Clock

import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.slick.jdbc.{ StaticQuery => Q }
import scala.slick.util.CloseableIterator
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._

@ImplementedBy(classOf[InvitationRepoImpl])
trait InvitationRepo extends Repo[Invitation] with RepoWithDelete[Invitation] with ExternalIdColumnFunction[Invitation] with SeqNumberFunction[Invitation] {
  def getAdminAccepted()(implicit session: RSession): Seq[Invitation]
  def invitationsPage(page: Int = 0, size: Int = 20, showState: Option[State[Invitation]] = None)(implicit session: RSession): Seq[(Option[Invitation], SocialUserInfo)]
  def getByUser(urlId: Id[User])(implicit session: RSession): Seq[Invitation]
  def countByUser(urlId: Id[User])(implicit session: RSession): Int
  def getByRecipientSocialUserId(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Seq[Invitation]
  def getByRecipientEmailAddress(emailAddress: EmailAddress)(implicit session: RSession): Seq[Invitation]
  def getByRecipientSocialUserIdsAndEmailAddresses(socialUserInfoIds: Set[Id[SocialUserInfo]], emailAddresses: Set[EmailAddress])(implicit session: RSession): Seq[Invitation]
  def getBySenderIdAndRecipientSocialUserId(senderId: Id[User], socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation]
  def getBySenderIdAndRecipientEmailAddress(senderId: Id[User], emailAddress: EmailAddress)(implicit session: RSession): Option[Invitation]
  def getLastInvitedAtBySenderIdAndRecipientSocialUserIds(senderId: Id[User], socialUserInfoIds: Seq[Id[SocialUserInfo]])(implicit session: RSession): Map[Id[SocialUserInfo], DateTime]
  def getLastInvitedAtBySenderIdAndRecipientEmailAddresses(senderId: Id[User], emailAddresses: Seq[EmailAddress])(implicit session: RSession): Map[EmailAddress, DateTime]
  def getBySenderId(senderId: Id[User])(implicit session: RSession): Seq[Invitation]
  def getBySenderIdIter(senderId: Id[User], max: Int)(implicit session: RSession): CloseableIterator[Invitation]
  def getSocialInvitesBySenderId(senderId: Id[User])(implicit session: RSession): Seq[Invitation]
  def getEmailInvitesBySenderId(senderId: Id[User])(implicit session: RSession): Seq[Invitation]
  def getRecentInvites(since: DateTime = currentDateTime.minusDays(1))(implicit session: RSession): List[Invitation]
}

@Singleton
class InvitationRepoImpl @Inject() (
  val db: DataBaseComponent,
  val userRepo: UserRepoImpl,
  val socialUserInfoRepo: SocialUserInfoRepoImpl,
  val clock: Clock,
  override protected val changeListener: Option[RepoModification.Listener[Invitation]])
    extends DbRepo[Invitation] with DbRepoWithDelete[Invitation] with InvitationRepo with ExternalIdColumnDbFunction[Invitation] with SeqNumberDbFunction[Invitation] {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = InvitationTable
  case class InvitationTable(tag: Tag) extends RepoTable[Invitation](db, tag, "invitation") with ExternalIdColumn[Invitation] with SeqNumberColumn[Invitation] {
    def senderUserId = column[Id[User]]("sender_user_id", O.Nullable)
    def recipientSocialUserId = column[Id[SocialUserInfo]]("recipient_social_user_id", O.Nullable)
    def recipientEmailAddress = column[EmailAddress]("recipient_email_address", O.Nullable)
    def lastSentAt = column[DateTime]("last_sent_at", O.Nullable)
    def timesSent = column[Int]("times_sent", O.NotNull)

    def * = (id.?, createdAt, updatedAt, timesSent, lastSentAt.?, externalId, senderUserId.?, recipientSocialUserId.?, recipientEmailAddress.?, state, seq) <> ((Invitation.apply _).tupled, Invitation.unapply _)
  }

  def table(tag: Tag) = new InvitationTable(tag)

  private implicit val userIdTypeMapper = userRepo.idMapper
  private implicit val userStateMapper = userRepo.stateTypeMapper

  override def save(invitation: Invitation)(implicit session: RWSession): Invitation = {
    val toSave = invitation.copy(seq = deferredSeqNum())
    super.save(toSave)
  }

  override def deleteCache(model: Invitation)(implicit session: RSession): Unit = {}
  override def invalidateCache(model: Invitation)(implicit session: RSession): Unit = {}

  // TODO: add support for econtactId
  def invitationsPage(page: Int = 0, size: Int = 20, showState: Option[State[Invitation]] = None)(implicit session: RSession): Seq[(Option[Invitation], SocialUserInfo)] = {
    val showPending = !showState.exists(_ != InvitationStates.ACCEPTED)
    val query =
      s"""
        | SELECT
        |   invitation.id AS invitation_id,
        |   social_user_info.id AS social_user_id
        | FROM social_user_info
        | LEFT JOIN user ON user.id = social_user_info.user_id
        | LEFT JOIN invitation ON invitation.recipient_social_user_id = social_user_info.id
        | WHERE
        |   (user.state = 'pending' AND $showPending) OR
        |   (invitation.id IS NOT NULL AND (invitation.state = '${showState.orNull}' OR ${showState.isEmpty}))
        | ORDER BY invitation.created_at DESC, user.created_at DESC
        | LIMIT $size
        | OFFSET ${size * page};
      """.stripMargin
    val rs = session.getPreparedStatement(query).executeQuery()
    val results = new mutable.ArrayBuffer[(Option[Id[Invitation]], Id[SocialUserInfo])]
    while (rs.next()) {
      results += Option(rs.getLong("invitation_id")).filterNot(_ => rs.wasNull()).map(Id[Invitation]) ->
        Id[SocialUserInfo](rs.getLong("social_user_id"))
    }
    results.map {
      case (Some(invId), suid) => (Some(get(invId)), socialUserInfoRepo.get(suid))
      case (None, suid) => (None, socialUserInfoRepo.get(suid))
    }
  }

  // get invitations accepted by admin but not joined
  def getAdminAccepted()(implicit session: RSession): Seq[Invitation] =
    (for (i <- rows if i.state === InvitationStates.ADMIN_ACCEPTED) yield i).list

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Invitation] =
    (for (b <- rows if b.senderUserId === userId && b.state =!= InvitationStates.INACTIVE) yield b).list

  def countByUser(userId: Id[User])(implicit session: RSession): Int = {
    Q.queryNA[Int](s"select count(*) from invitation where sender_user_id=$userId and state != '${InvitationStates.INACTIVE.value}'").first
  }

  def getByRecipientSocialUserId(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Seq[Invitation] = {
    (for (b <- rows if b.recipientSocialUserId === socialUserInfoId) yield b).list
  }

  def getByRecipientEmailAddress(emailAddress: EmailAddress)(implicit session: RSession): Seq[Invitation] = {
    (for { row <- rows if row.recipientEmailAddress === emailAddress } yield row).list
  }

  def getByRecipientSocialUserIdsAndEmailAddresses(socialUserInfoId: Set[Id[SocialUserInfo]], emailAddress: Set[EmailAddress])(implicit session: RSession): Seq[Invitation] = {
    (for { row <- rows if row.recipientSocialUserId.inSet(socialUserInfoId) || row.recipientEmailAddress.inSet(emailAddress) } yield row).list
  }

  def getBySenderIdAndRecipientSocialUserId(senderId: Id[User], socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation] = {
    (for (b <- rows if b.senderUserId === senderId && b.recipientSocialUserId === socialUserInfoId) yield b).firstOption
  }

  def getBySenderIdAndRecipientEmailAddress(senderId: Id[User], emailAddress: EmailAddress)(implicit session: RSession): Option[Invitation] = {
    (for (b <- rows if b.senderUserId === senderId && b.recipientEmailAddress === emailAddress) yield b).firstOption
  }

  def getLastInvitedAtBySenderIdAndRecipientSocialUserIds(senderId: Id[User], socialUserInfoIds: Seq[Id[SocialUserInfo]])(implicit session: RSession): Map[Id[SocialUserInfo], DateTime] = {
    if (socialUserInfoIds.isEmpty) {
      Map.empty
    } else {
      val query = for (i <- rows if i.senderUserId === senderId && i.recipientSocialUserId.inSet(socialUserInfoIds) && i.state =!= InvitationStates.INACTIVE && i.lastSentAt.isDefined)
        yield (i.recipientSocialUserId, i.lastSentAt) // using createdAt for now (user cannot currently re-invite same social connection)
      Map(query.list: _*)
    }
  }

  def getLastInvitedAtBySenderIdAndRecipientEmailAddresses(senderId: Id[User], emailAddresses: Seq[EmailAddress])(implicit session: RSession): Map[EmailAddress, DateTime] = {
    if (emailAddresses.isEmpty) {
      Map.empty
    } else {
      var query = for (i <- rows if i.senderUserId === senderId && i.recipientEmailAddress.inSet(emailAddresses) && i.state =!= InvitationStates.INACTIVE && i.lastSentAt.isDefined)
        yield (i.recipientEmailAddress, i.lastSentAt) // using createdAt for now (user cannot currently re-invite same e-contact)
      Map(query.list: _*)
    }
  }

  def getBySenderIdIter(senderId: Id[User], max: Int)(implicit session: RSession): CloseableIterator[Invitation] = {
    (for (b <- rows if b.senderUserId === senderId) yield b).iteratorTo(max)
  }

  def getBySenderId(senderId: Id[User])(implicit session: RSession): Seq[Invitation] = {
    (for (b <- rows if b.senderUserId === senderId) yield b).list
  }

  def getSocialInvitesBySenderId(senderId: Id[User])(implicit session: RSession): Seq[Invitation] = {
    (for (b <- rows if b.senderUserId === senderId && b.recipientSocialUserId.isDefined) yield b).list
  }

  def getEmailInvitesBySenderId(senderId: Id[User])(implicit session: RSession): Seq[Invitation] = {
    (for (b <- rows if b.senderUserId === senderId && b.recipientEmailAddress.isDefined) yield b).list
  }

  def getRecentInvites(since: DateTime)(implicit session: RSession): List[Invitation] = {
    (for (b <- rows if b.state.inSet(Set(InvitationStates.ACTIVE, InvitationStates.ACCEPTED)) && b.updatedAt > since && b.lastSentAt.isDefined) yield b).list
  }
}

trait InvitationSequencingPlugin extends SequencingPlugin

class InvitationSequencingPluginImpl @Inject() (
  override val actor: ActorInstance[InvitationSequencingActor],
  override val scheduling: SchedulingProperties) extends InvitationSequencingPlugin

@Singleton
class InvitationSequenceNumberAssigner @Inject() (db: Database, repo: InvitationRepo, airbrake: AirbrakeNotifier) extends DbSequenceAssigner[Invitation](db, repo, airbrake)
class InvitationSequencingActor @Inject() (
  assigner: InvitationSequenceNumberAssigner,
  airbrake: AirbrakeNotifier) extends SequencingActor(assigner, airbrake)
