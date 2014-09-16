package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.curator.model.{ CuratorLibraryMembershipInfoStates, CuratorLibraryMembershipInfo, CuratorLibraryMembershipInfoRepo }
import com.keepit.model.{ LibraryAccess, User, Library, LibraryKind }
import org.specs2.mutable.Specification

class CuratorLibraryMembershipInfoRepoTest extends Specification with CuratorTestInjector {
  "CuratorLibraryMembershipInfoRepoTest" should {
    "get list of libraries correctly" in {
      withDb() { implicit injector =>
        val repo = inject[CuratorLibraryMembershipInfoRepo]
        db.readWrite { implicit s =>
          repo.save(CuratorLibraryMembershipInfo(
            userId = Id[User](42),
            libraryId = Id[Library](1),
            access = LibraryAccess.READ_WRITE,
            state = CuratorLibraryMembershipInfoStates.ACTIVE
          ))
          repo.save(CuratorLibraryMembershipInfo(
            userId = Id[User](42),
            libraryId = Id[Library](2),
            access = LibraryAccess.READ_WRITE,
            state = CuratorLibraryMembershipInfoStates.ACTIVE
          ))
          repo.save(CuratorLibraryMembershipInfo(
            userId = Id[User](43),
            libraryId = Id[Library](1),
            access = LibraryAccess.READ_WRITE,
            state = CuratorLibraryMembershipInfoStates.ACTIVE
          ))

          val libs1 = repo.getLibrariesByUserId(Id[User](42))
          libs1.size === 2
          libs1(0).id === 1
          libs1(1).id === 2

          val libs2 = repo.getLibrariesByUserId(Id[User](43))
          libs2.size === 1
          libs2(0).id === 1
        }
      }
    }
  }
}
