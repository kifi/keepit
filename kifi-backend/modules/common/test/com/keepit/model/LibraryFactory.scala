package com.keepit.model

import java.util.concurrent.atomic.AtomicLong

import com.keepit.common.db.{ ExternalId, Id, State }
import com.keepit.model.LibraryVisibility.{ PUBLISHED, SECRET, DISCOVERABLE }
import org.apache.commons.lang3.RandomStringUtils.random

object LibraryFactory {
  private[this] val idx = new AtomicLong(System.currentTimeMillis() % 100)

  def library(): PartialLibrary = {
    new PartialLibrary(Library(id = Some(Id[Library](idx.incrementAndGet())), name = random(5), slug = LibrarySlug(random(5)),
      visibility = LibraryVisibility.SECRET, ownerId = Id[User](idx.incrementAndGet()), memberCount = 1))
  }

  def libraries(count: Int): Seq[PartialLibrary] = List.fill(count)(library())

  class PartialLibrary private[LibraryFactory] (library: Library) {
    def withId(id: Id[Library]) = new PartialLibrary(library.copy(id = Some(id)))
    def withId(id: Int) = new PartialLibrary(library.copy(id = Some(Id[Library](id))))
    def withUser(id: Int) = new PartialLibrary(library.copy(ownerId = Id[User](id)))
    def withMemberCount(memberCount: Int) = new PartialLibrary(library.copy(memberCount = memberCount))
    def withUser(id: Id[User]) = new PartialLibrary(library.copy(ownerId = id))
    def withUser(user: User) = new PartialLibrary(library.copy(ownerId = user.id.get))
    def withName(name: String) = new PartialLibrary(library.copy(name = name))
    def withSlug(slug: String) = new PartialLibrary(library.copy(slug = LibrarySlug(slug)))
    def withColor(color: String): PartialLibrary = withColor(HexColor(color))
    def withColor(color: HexColor) = new PartialLibrary(library.copy(color = Some(color)))
    def withState(state: State[Library]) = new PartialLibrary(library.copy(state = state))
    def secret() = new PartialLibrary(library.copy(visibility = SECRET))
    def published() = new PartialLibrary(library.copy(visibility = PUBLISHED))
    def discoverable() = new PartialLibrary(library.copy(visibility = DISCOVERABLE))
    def get: Library = library
  }

  implicit class PartialLibrarySeq(users: Seq[PartialLibrary]) {
    def get: Seq[Library] = users.map(_.get)
  }

}
