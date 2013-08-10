package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.time.Clock

@ImplementedBy(classOf[CommentReadRepoImpl])
trait CommentReadRepo extends Repo[CommentRead] {
  def getByUserAndUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[CommentRead]
  def getByUserAndParent(userId: Id[User], parentId: Id[Comment])(implicit session: RSession): Option[CommentRead]
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[CommentRead]
}

@Singleton
class CommentReadRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  commentRepo: CommentRepo)
  extends DbRepo[CommentRead] with CommentReadRepo {
  import FortyTwoTypeMappers._
  import scala.slick.lifted.Query
  import db.Driver.Implicit._
  import DBSession._

  override val table = new RepoTable[CommentRead](db, "comment_read") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.NotNull)
    def parentId = column[Id[Comment]]("parent_id", O.Nullable)
    def lastReadId = column[Id[Comment]]("last_read_id", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ uriId ~ parentId.? ~ lastReadId ~ state <> (CommentRead, CommentRead.unapply _)
  }

  def getByUserAndParent(userId: Id[User], parentId: Id[Comment])(implicit session: RSession): Option[CommentRead] =
    (for (f <- table if f.userId === userId && f.parentId === parentId && f.state === CommentReadStates.ACTIVE) yield f).firstOption

  def getByUserAndUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Option[CommentRead] =
    (for (f <- table if f.userId === userId && f.uriId === uriId && f.parentId.isNull && f.state === CommentReadStates.ACTIVE) yield f).firstOption

  // used for "grandfathering"
  def getByUri(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[CommentRead] =
    (for (r <- table if r.uriId === uriId) yield r).list
}
