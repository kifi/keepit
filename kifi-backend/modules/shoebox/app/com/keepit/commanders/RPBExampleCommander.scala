package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.model._

sealed trait DBContext
object DBContext {
  case class NoSession(dummy: Int) extends DBContext
  case class YesSession(session: RSession) extends DBContext
}

@Singleton
class RPBExampleCommander @Inject() (
    db: Database,
    keepRepo: KeepRepo) {

  // For the purposes of this example, pretend that our repos used this convention
  def safeDb[T](f: DBContext.YesSession => T)(implicit ctx: DBContext.NoSession) = db.readOnlyMaster { s => f(DBContext.YesSession(s)) }
  def getKeep(keepId: Id[Keep])(implicit ctx: DBContext.YesSession) = keepRepo.get(keepId)(ctx.session)
  def getCount()(implicit ctx: DBContext.YesSession) = keepRepo.count(ctx.session)

  // If a method opens a session, you must declare it as such
  def openSession(keepId: Id[Keep])(implicit ctx: DBContext.NoSession): Keep = {
    safeDb { implicit s =>
      getKeep(keepId)
    }
  }

  def deferSession(keepId: Id[Keep])(implicit ctx: DBContext.NoSession): Keep = {
    safeDb { implicit s =>
      useDeferredSession(keepId)
    }
  }
  def useDeferredSession(keepId: Id[Keep])(implicit ctx: DBContext.YesSession): Keep = {
    doSomethingThatSeemsSafe()
    getKeep(keepId)
  }
  def doSomethingThatSeemsSafe()(implicit ctx: DBContext.YesSession): Unit = {
    println("i am totally safe")
    println("when I was written, no one assumed I would misbehave ever")
    println("but now I am angsty and want to do my own thing")
    // Try to uncomment these lines
    /*
    safeDb { implicit s =>
      println("ha, take that dad")
      println(getCount)
    }
    */
  }
}
