package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time.Clock

// Copied wholesale from LibraryImageRequestRepo
@ImplementedBy(classOf[OrganizationAvatarRequestRepoImpl])
trait OrganizationAvatarRequestRepo extends Repo[OrganizationAvatarRequest] {
  def getByToken(token: String)(implicit session: RSession): Option[OrganizationAvatarRequest]
  def updateState(id: Id[OrganizationAvatarRequest], state: State[OrganizationAvatarRequest])(implicit session: RWSession): Unit
}

@Singleton
class OrganizationAvatarRequestRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[OrganizationAvatarRequest] with OrganizationAvatarRequestRepo {

  import db.Driver.simple._

  type RepoImpl = OrganizationAvatarRequestTable
  class OrganizationAvatarRequestTable(tag: Tag) extends RepoTable[OrganizationAvatarRequest](db, tag, "library_image_request") {

    def libraryId = column[Id[Organization]]("library_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def failureCode = column[String]("failure_code", O.Nullable)
    def failureReason = column[String]("failure_reason", O.Nullable)
    def successHash = column[ImageHash]("success_hash", O.Nullable)
    def source = column[ImageSource]("source", O.NotNull)

    def idxSourceFileHashSize = index("library_image_request_u_token", token, unique = true)

    def * = (id.?, createdAt, updatedAt, state, libraryId, token, failureCode.?, failureReason.?, successHash.?, source) <> ((OrganizationAvatarRequest.apply _).tupled, OrganizationAvatarRequest.unapply _)
  }

  def table(tag: Tag) = new OrganizationAvatarRequestTable(tag)
  initTable()

  override def invalidateCache(model: OrganizationAvatarRequest)(implicit session: RSession): Unit = {}

  override def deleteCache(model: OrganizationAvatarRequest)(implicit session: RSession): Unit = {}

  private val getByTokenCompiled = Compiled { token: Column[String] =>
    for (r <- rows if r.token === token) yield r
  }
  def getByToken(token: String)(implicit session: RSession): Option[OrganizationAvatarRequest] = {
    getByTokenCompiled(token).firstOption
  }

  private val getIdAndStateCompiled = Compiled { id: Column[Id[OrganizationAvatarRequest]] =>
    for (r <- rows if r.id === id) yield (r.id, r.state)
  }
  def updateState(id: Id[OrganizationAvatarRequest], state: State[OrganizationAvatarRequest])(implicit session: RWSession): Unit = {
    getIdAndStateCompiled(id).update((id, state))
  }

}
