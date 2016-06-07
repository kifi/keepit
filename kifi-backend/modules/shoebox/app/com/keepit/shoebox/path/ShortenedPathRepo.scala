package com.keepit.shoebox.path

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, State }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddressHash, EmailAddress }
import com.keepit.common.path.Path
import com.keepit.common.time.Clock
import org.joda.time.DateTime

@ImplementedBy(classOf[ShortenedPathRepoImpl])
trait ShortenedPathRepo extends Repo[ShortenedPath] {
  def intern(path: Path)(implicit session: RWSession): ShortenedPath
}

@Singleton
class ShortenedPathRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends ShortenedPathRepo with DbRepo[ShortenedPath] with Logging {

  override def deleteCache(ktu: ShortenedPath)(implicit session: RSession) {}
  override def invalidateCache(ktu: ShortenedPath)(implicit session: RSession) {}

  import db.Driver.simple._

  type RepoImpl = ShortenedPathTable
  class ShortenedPathTable(tag: Tag) extends RepoTable[ShortenedPath](db, tag, "shortened_path") {
    def path = column[Path]("path", O.NotNull)
    def pathHash = column[Int]("path_hash", O.NotNull)

    def * = (id.?, createdAt, updatedAt, state, path, pathHash) <> ((fromDbRow _).tupled, toDbRow)
  }

  private def fromDbRow(id: Option[Id[ShortenedPath]], createdAt: DateTime, updatedAt: DateTime, state: State[ShortenedPath],
    path: Path, pathHash: Int) = {
    ShortenedPath(id, createdAt, updatedAt, state, path)
  }

  private def toDbRow(sp: ShortenedPath) = {
    Some((sp.id, sp.createdAt, sp.updatedAt, sp.state, sp.path, PathHash(sp.path)))
  }

  def table(tag: Tag) = new ShortenedPathTable(tag)
  initTable()

  def activeRows = rows.filter(_.state === ShortenedPathStates.ACTIVE)
  def intern(path: Path)(implicit session: RWSession): ShortenedPath = {
    val hash = PathHash(path)
    rows.filter(r => r.pathHash === hash && r.path === path).firstOption match {
      case Some(sp) if sp.isActive => sp
      case inactiveOpt => save(ShortenedPath(path = path).copy(id = inactiveOpt.map(_.id.get)))
    }
  }
}
