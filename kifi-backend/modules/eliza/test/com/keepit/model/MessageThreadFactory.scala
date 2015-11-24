package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.eliza.model.{ MessageThreadRepo, MessageThread }
import org.apache.commons.lang3.RandomStringUtils

object MessageThreadFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def thread(): PartialMessageThread = {
    val url = s"www.${RandomStringUtils.randomAlphabetic(10)}.com"
    PartialMessageThread(MessageThread(
      uriId = Some(Id(idx.incrementAndGet())),
      url = Some(url),
      nUrl = Some(url),
      pageTitle = Some(RandomStringUtils.randomAlphabetic(5).toUpperCase),
      participants = None,
      participantsHash = None,
      keepId = None
    ))
  }

  case class PartialMessageThread(th: MessageThread) {
    def withKeep(keepId: Id[Keep]) = this.copy(th = th.copy(keepId = Some(keepId)))
    def saved(implicit injector: Injector, session: RWSession): MessageThread = {
      injector.getInstance(classOf[MessageThreadRepo]).save(th)
    }
  }

  def threads(count: Int) = List.fill(count)(thread())
}
