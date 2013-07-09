package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{Id, State}
import com.keepit.common.time.Clock
import scala.Some
import scala.collection.mutable

@ImplementedBy(classOf[InvitationRepoImpl])
trait InvitationRepo extends Repo[Invitation] with ExternalIdColumnFunction[Invitation] {
  def getAdminAccepted()(implicit session: RSession): Seq[Invitation]
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
    (for (i <- table if i.state === InvitationStates.ADMIN_ACCEPTED) yield i).list

  def getByUser(userId: Id[User])(implicit session: RSession): Seq[Invitation] =
    (for(b <- table if b.senderUserId === userId && b.state =!= InvitationStates.INACTIVE) yield b).list

  def getByRecipient(socialUserInfoId: Id[SocialUserInfo])(implicit session: RSession): Option[Invitation] = {
    (for(b <- table if b.recipientSocialUserId === socialUserInfoId) yield b).take(1).firstOption
  }

}

