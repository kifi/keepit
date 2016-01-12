package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick._
import com.keepit.model._

@Singleton
class RPBExampleCommander @Inject() (
    db: SafeDatabase,
    keepRepo: KeepRepo) {

  // For the purposes of this example, pretend that our repos used this convention
  def getKeep(keepId: Id[Keep])(implicit ctx: DBContext.Read) = keepRepo.get(keepId)(ctx.session)
  def getCount()(implicit ctx: DBContext.Read) = keepRepo.count(ctx.session)

  // If a method opens a session, you must declare it as such
  def openSession(keepId: Id[Keep])(implicit ctx: DBContext.Clean): Keep = {
    db.read { implicit s =>
      getKeep(keepId)
    }
  }

  def deferSession(keepId: Id[Keep])(implicit ctx: DBContext.Clean): Keep = {
    db.read { implicit s =>
      useDeferredSession(keepId)
    }
  }
  def useDeferredSession(keepId: Id[Keep])(implicit ctx: DBContext.Read): Keep = {
    doSomethingThatIsActuallySafe()
    doSomethingThatSeemsSafe()
    getKeep(keepId)
  }
  def doSomethingThatIsActuallySafe(): Unit = {
    println("i am really safe")
  }
  def doSomethingThatSeemsSafe()(implicit ctx: DBContext.Read): Unit = {
    println("i am definitely unsafe")
    println("when I was written, no one assumed I would misbehave ever")
    println("but now I am angsty and want to do my own thing")
    // Try to uncomment these lines
    /*
    db.write { implicit s =>
      println("ha, take that dad")
      println(getCount)
    }
    */
  }
}
