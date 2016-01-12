package com.keepit.common.db.slick

import com.google.inject.Inject
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }

sealed trait DBContext
object DBContext {
  def takeResponsibility(who: String): Clean = Clean(who)

  case class Clean(signature: String) extends DBContext
  case class Read(session: RSession) extends DBContext
  case class Write(session: RWSession) extends DBContext

  implicit def writeToRead(w: Write): Read = Read(w.session)
}

class SafeDatabase @Inject() (db: Database) {
  def read[T](fn: DBContext.Read => T)(implicit ctx: DBContext.Clean): T = db.readOnlyMaster { s => fn(DBContext.Read(s)) }
  def write[T](fn: DBContext.Write => T)(implicit ctx: DBContext.Clean): T = db.readWrite { s => fn(DBContext.Write(s)) }
}
