package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.eliza.model.{ MessageThreadParticipants, MessageThreadRepo, MessageThread }
import org.apache.commons.lang3.RandomStringUtils

object MessageThreadFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def thread(): PartialMessageThread = {
    val url = s"www.${RandomStringUtils.randomAlphabetic(10)}.com"
    val starter = Id[User](idx.incrementAndGet())
    PartialMessageThread(MessageThread(
      uriId = Id(idx.incrementAndGet()),
      url = url,
      nUrl = url,
      pageTitle = Some(RandomStringUtils.randomAlphabetic(5).toUpperCase),
      startedBy = Some(starter),
      participants = MessageThreadParticipants(Set(starter)),
      keepId = None
    ))
  }

  case class PartialMessageThread(th: MessageThread) {
    def withKeep(keepId: Id[Keep]) = this.copy(th = th.copy(keepId = Some(keepId)))
    def withTitle(newTitle: String) = this.copy(th = th.copy(pageTitle = Some(newTitle)))
    def withUri(uriId: Id[NormalizedURI]) = this.copy(th = th.copy(uriId = uriId))
    def withOnlyStarter(startedBy: Id[User]) = this.copy(th = th.copy(startedBy = Some(startedBy), participants = MessageThreadParticipants(Set(startedBy))))
    def withUsers(users: Id[User]*) = this.copy(th = th.withParticipants(currentDateTime, users))
    def saved(implicit injector: Injector, session: RWSession): MessageThread = {
      injector.getInstance(classOf[MessageThreadRepo]).save(th)
    }
  }

  def threads(count: Int) = List.fill(count)(thread())
}
