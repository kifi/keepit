package com.keepit.curator

import com.keepit.common.db.{ Id }
import com.keepit.curator.model.{ CuratorKeepInfoStates, CuratorKeepInfo, CuratorKeepInfoRepo }
import com.keepit.model.{ Keep, User, NormalizedURI }
import org.specs2.mutable.Specification

class CuratorKeepInfoRepoTest extends Specification with CuratorTestInjector {
  "CuratorKeepInfoRepoTest" should {

    "check if uri discoverable" in {
      withDb() { implicit injector =>
        val repo = inject[CuratorKeepInfoRepo]
        db.readWrite { implicit s =>
          repo.save(CuratorKeepInfo(
            uriId = Id[NormalizedURI](1),
            userId = Id[User](42),
            keepId = Id[Keep](1),
            state = CuratorKeepInfoStates.ACTIVE,
            discoverable = false))

          val result1 = repo.checkDiscoverableByUriId(Id[NormalizedURI](1))

          result1 === false

          repo.save(CuratorKeepInfo(
            uriId = Id[NormalizedURI](1),
            userId = Id[User](43),
            keepId = Id[Keep](2),
            state = CuratorKeepInfoStates.ACTIVE,
            discoverable = true))

          val result2 = repo.checkDiscoverableByUriId(Id[NormalizedURI](1))

          result2 === true
        }

      }
    }
  }
}
