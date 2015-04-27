package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.{ DbRepo, DataBaseComponent, Repo }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[LibraryImageRequestRepoImpl])
trait LibraryImageRequestRepo extends Repo[LibraryImageRequest] {
  def getByToken(token: String)(implicit session: RSession): Option[LibraryImageRequest]
  def updateState(id: Id[LibraryImageRequest], state: State[LibraryImageRequest])(implicit session: RWSession): Unit
}

@Singleton
class LibraryImageRequestRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[LibraryImageRequest] with LibraryImageRequestRepo {

  import db.Driver.simple._

  type RepoImpl = LibraryImageRequestTable
  class LibraryImageRequestTable(tag: Tag) extends RepoTable[LibraryImageRequest](db, tag, "library_image_request") {

    def libraryId = column[Id[Library]]("library_id", O.NotNull)
    def token = column[String]("token", O.NotNull)
    def failureCode = column[String]("failure_code", O.Nullable)
    def failureReason = column[String]("failure_reason", O.Nullable)
    def successHash = column[ImageHash]("success_hash", O.Nullable)
    def source = column[ImageSource]("source", O.NotNull)

    def idxSourceFileHashSize = index("library_image_request_u_token", token, unique = true)

    def * = (id.?, createdAt, updatedAt, state, libraryId, token, failureCode.?, failureReason.?, successHash.?, source) <> ((LibraryImageRequest.apply _).tupled, LibraryImageRequest.unapply _)
  }

  def table(tag: Tag) = new LibraryImageRequestTable(tag)
  initTable()

  override def invalidateCache(model: LibraryImageRequest)(implicit session: RSession): Unit = {}

  override def deleteCache(model: LibraryImageRequest)(implicit session: RSession): Unit = {}

  private val getByTokenCompiled = Compiled { token: Column[String] =>
    for (r <- rows if r.token === token) yield r
  }
  def getByToken(token: String)(implicit session: RSession): Option[LibraryImageRequest] = {
    getByTokenCompiled(token).firstOption
  }

  private val getIdAndStateCompiled = Compiled { id: Column[Id[LibraryImageRequest]] =>
    for (r <- rows if r.id === id) yield (r.id, r.state)
  }
  def updateState(id: Id[LibraryImageRequest], state: State[LibraryImageRequest])(implicit session: RWSession): Unit = {
    getIdAndStateCompiled(id).update((id, state))
  }

}
