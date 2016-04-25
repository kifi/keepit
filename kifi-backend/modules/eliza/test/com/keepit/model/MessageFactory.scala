package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.google.inject.Injector
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.eliza.model._
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime

object MessageFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)
  def message(): PartialElizaMessage = {
    PartialElizaMessage(ElizaMessage(
      keepId = Id(idx.incrementAndGet()),
      commentIndexOnKeep = Some(0),
      from = MessageSender.User(Id(idx.incrementAndGet())),
      messageText = RandomStringUtils.randomAlphabetic(50),
      source = None,
      auxData = None,
      sentOnUrl = None,
      sentOnUriId = None
    ))
  }
  def messages(n: Int) = List.fill(n)(message())

  case class PartialElizaMessage(em: ElizaMessage) {
    def withThread(thread: MessageThread) = this.copy(em = em.copy(keepId = thread.keepId))
    def withUserThread(ut: UserThread) = this.copy(em = em.copy(keepId = ut.keepId, from = MessageSender.User(ut.user)))
    def from(sender: MessageSender) = this.copy(em = em.copy(from = sender))
    def withText(text: String) = this.copy(em = em.copy(messageText = text))
    def withCreatedAt(time: DateTime) = this.copy(em = em.copy(createdAt = time))
    def saved(implicit injector: Injector, session: RWSession): ElizaMessage = {
      injector.getInstance(classOf[MessageRepo]).save(em)
    }
  }
}
