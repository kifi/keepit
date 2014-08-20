package com.keepit.curator

import com.keepit.curator.model.{ PublicFeedRepo, PublicFeed }
import org.specs2.mutable.Specification

class PublicFeedRepoTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def setup(): Seq[PublicFeed] = {
    val rec1 = makePublicFeed(1, 0.15f)
    val rec2 = makePublicFeed(2, 0.99f)
    val rec3 = makePublicFeed(3, 0.5f)
    Seq(rec1, rec2, rec3)
  }

  "PublicFeedRepo" should {
    "get top master score of recommendations" in {
      withDb() { implicit injector =>
        val repo = inject[PublicFeedRepo]
        db.readWrite { implicit s =>
          val feeds = setup()
          repo.save(feeds(0))
          repo.save(feeds(1))
          repo.save(feeds(2))
        }

        db.readOnlyMaster { implicit s =>
          val feeds = repo.getByTopMasterScore(2)
          feeds(0).publicMasterScore === 0.99f
          feeds(1).publicMasterScore === 0.5f
        }
      }
    }
  }

}
