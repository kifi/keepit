package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ ExternalId, Id, State }
import com.keepit.common.time._
import com.keepit.model.LibraryVisibility.{ PUBLISHED, SECRET, DISCOVERABLE }
import org.apache.commons.lang3.RandomStringUtils.random

object LibraryFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def library(): PartialLibrary = {
    new PartialLibrary(Library(id = Some(Id[Library](idx.incrementAndGet())), name = random(5), slug = LibrarySlug(random(5)),
      visibility = LibraryVisibility.SECRET, ownerId = Id[User](idx.incrementAndGet()), memberCount = 1, keepCount = 0))
  }

  def libraries(count: Int): Seq[PartialLibrary] = List.fill(count)(library())

  class PartialLibrary private[LibraryFactory] (library: Library) {
    def withId(id: Id[Library]) = new PartialLibrary(library.copy(id = Some(id)))
    def withId(id: Int) = new PartialLibrary(library.copy(id = Some(Id[Library](id))))
    def withUser(id: Int) = new PartialLibrary(library.copy(ownerId = Id[User](id)))
    def withUser(id: Id[User]) = new PartialLibrary(library.copy(ownerId = id))
    def withUser(user: User) = new PartialLibrary(library.copy(ownerId = user.id.get))
    def withMemberCount(memberCount: Int) = new PartialLibrary(library.copy(memberCount = memberCount, lastKept = Some(currentDateTime)))
    def withKeepCount(keepCount: Int) = new PartialLibrary(library.copy(keepCount = keepCount))
    def withName(name: String) = new PartialLibrary(library.copy(name = name))
    def withDesc(desc: String) = new PartialLibrary(library.copy(description = Some(desc)))
    def withSlug(slug: String) = new PartialLibrary(library.copy(slug = LibrarySlug(slug)))
    def withColor(color: String): PartialLibrary = withColor(LibraryColor(color))
    def withColor(color: LibraryColor) = new PartialLibrary(library.copy(color = Some(color)))
    def withKind(kind: LibraryKind) = new PartialLibrary(library.copy(kind = kind))
    def withState(state: State[Library]) = new PartialLibrary(library.copy(state = state))
    def withVisibility(viz: LibraryVisibility) = new PartialLibrary(library.copy(visibility = viz))
    def secret() = new PartialLibrary(library.copy(visibility = SECRET))
    def published() = new PartialLibrary(library.copy(visibility = PUBLISHED))
    def discoverable() = new PartialLibrary(library.copy(visibility = DISCOVERABLE))
    def withLastKept() = new PartialLibrary(library.copy(lastKept = Some(currentDateTime)))
    def get: Library = library
  }

  implicit class PartialLibrarySeq(users: Seq[PartialLibrary]) {
    def get: Seq[Library] = users.map(_.get)
  }

}
