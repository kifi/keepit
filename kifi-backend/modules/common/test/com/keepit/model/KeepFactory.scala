package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ SequenceNumber, ExternalId, Id, State }
import com.keepit.common.time._
import org.apache.commons.lang3.RandomStringUtils.random
import org.joda.time.DateTime

object KeepFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def keep(): PartialKeep = {
    new PartialKeep(Keep(id = Some(Id[Keep](-1 * idx.incrementAndGet())),
      uriId = Id[NormalizedURI](-1 * idx.incrementAndGet()),
      inDisjointLib = true,
      urlId = Id[URL](-1 * idx.incrementAndGet()),
      url = s"http://${random(5)}.com/${random(5)}",
      visibility = LibraryVisibility.PUBLISHED,
      userId = Id[User](-1 * idx.incrementAndGet()),
      source = KeepSource.keeper,
      libraryId = None))
  }

  def keeps(count: Int): Seq[PartialKeep] = List.fill(count)(keep())

  class PartialKeep private[KeepFactory] (keep: Keep) {
    def withId(id: Id[Keep]) = new PartialKeep(keep.copy(id = Some(id)))
    def withId(id: Int) = new PartialKeep(keep.copy(id = Some(Id[Keep](id))))
    def withId(id: ExternalId[Keep]) = new PartialKeep(keep.copy(externalId = id))
    def withId(id: String) = new PartialKeep(keep.copy(externalId = ExternalId[Keep](id)))
    def withTitle(title: String) = new PartialKeep(keep.copy(title = Some(title)))
    def withLibrary(library: Library) = new PartialKeep(keep.copy(libraryId = library.id, userId = library.ownerId, visibility = library.visibility))
    def withLibrary(library: Id[Library]) = new PartialKeep(keep.copy(libraryId = Some(library)))
    def withState(state: State[Keep]) = new PartialKeep(keep.copy(state = state))
    def get: Keep = keep
  }

  implicit class PartialKeepSeq(keeps: Seq[PartialKeep]) {
    def get: Seq[Keep] = keeps.map(_.get)
  }

}
