package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.eliza.model._

object UserThreadFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def userThread(): PartialUserThread = {
    PartialUserThread(UserThread(
      user = Id[User](idx.incrementAndGet()),
      threadId = Id[MessageThread](idx.incrementAndGet()),
      uriId = Some(Id[NormalizedURI](idx.incrementAndGet())),
      lastSeen = None,
      unread = true,
      lastMsgFromOther = None,
      startedBy = Id[User](idx.incrementAndGet())
    ))
  }
  def userThreads(n: Int) = List.fill(n)(userThread())

  case class PartialUserThread(ut: UserThread) {
    def withThread(thread: MessageThread) = this.copy(ut = ut.copy(threadId = thread.id.get, startedBy = thread.startedBy))
    def forUser(user: User) = this.copy(ut = ut.copy(user = user.id.get))
    def saved(implicit injector: Injector, session: RWSession): UserThread = {
      injector.getInstance(classOf[UserThreadRepo]).save(ut)
    }
  }
}
