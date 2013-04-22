package com.keepit.model

import scala.collection.mutable

import org.joda.time.DateTime

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._

case class Invitation(
  id: Option[Id[Invitation]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[Invitation] = ExternalId(),
  senderUserId: Option[Id[User]],
  recipientSocialUserId: Id[SocialUserInfo],
  state: State[Invitation] = InvitationStates.ACTIVE
) extends ModelWithExternalId[Invitation] {
  def withId(id: Id[Invitation]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[Invitation]) = copy(state = state)
}

@ImplementedBy(classOf[InvitationRepoImpl])
trait InvitationRepo extends Repo[Invitation] with ExternalIdColumnFunction[Invitation] {
  def invitationsPage(page: Int = 0, size: Int = 20, showState: Option[State[Invitation]] = None)
    (implicit session: RSession): Seq[(Option[Invitation], SocialUserInfo)]
  def getByUser(urlId: Id[User])(implicit session: RSession): Seq[Invitation]
  def getByRecipient(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation]
}

@Singleton
class InvitationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val userRepo: UserRepoImpl,
    val socialUserInfoRepo: SocialUserInfoRepoImpl,
    val clock: Clock)
  extends DbRepo[Invitation] with InvitationRepo with ExternalIdColumnDbFunction[Invitation] {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[Invitation](db, "invitation") with ExternalIdColumn[Invitation] {
    def senderUserId = column[Option[Id[User]]]("sender_user_id", O.NotNull)
    def recipientSocialUserId = column[Id[SocialUserInfo]]("recipient_social_user_id", O.NotNull)

    def * = id.? ~ createdAt ~ updatedAt ~ externalId ~ senderUserId ~ recipientSocialUserId ~ state <> (Invitation, Invitation.unapply _)
  }

  private implicit val userIdTypeMapper = userRepo.idMapper
  private implicit val userStateMapper = userRepo.stateTypeMapper

  def invitationsPage(page: Int = 0, size: Int = 20, showState: Option[State[Invitation]] = None)
      (implicit session: RSession): Seq[(Option[Invitation], SocialUserInfo)] = {
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
        | ORDER BY invitation.updated_at DESC, user.created_at DESC
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

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Invitation] =
    (for(b <- table if b.senderUserId === userId && b.state =!= InvitationStates.INACTIVE) yield b).list

  def getByRecipient(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation] = {
    (for(b <- table if b.recipientSocialUserId === socialUserInfoId) yield b).take(1).firstOption
  }

}

object InvitationStates extends States[Invitation] {
  val ACCEPTED = State[Invitation]("accepted")
  val ADMIN_REJECTED = State[Invitation]("admin_rejected")
  val ADMIN_ACCEPTED = State[Invitation]("admin_accepted")
  val JOINED = State[Invitation]("joined")
}
