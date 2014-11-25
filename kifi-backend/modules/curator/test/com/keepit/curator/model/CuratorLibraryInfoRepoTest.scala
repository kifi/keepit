package com.keepit.curator.model

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.curator.{ CuratorTestHelpers, CuratorTestInjector }
import com.keepit.model.{ User, Library }
import org.specs2.mutable.Specification

class CuratorLibraryInfoRepoTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  "CuratorLibraryInfoRepoTest" should {
    "save and get objects" in {
      withDb() { implicit injector: Injector =>
        db.readWrite { implicit rw =>
          saveLibraryInfo(1, 42).id must beSome
          saveLibraryInfo(2, 43).id must beSome
        }

        db.readOnlyMaster { implicit rw =>
          val lib1 = inject[CuratorLibraryInfoRepo].getByLibraryId(Id[Library](1)).get
          lib1.ownerId === Id[User](42)

          val lib2 = inject[CuratorLibraryInfoRepo].getByLibraryId(Id[Library](2)).get
          lib2.ownerId === Id[User](43)
        }
      }
    }

  }
}
