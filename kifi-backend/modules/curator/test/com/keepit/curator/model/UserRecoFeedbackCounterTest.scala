package com.keepit.curator.model

import com.keepit.common.db.Id
import com.keepit.curator.CuratorTestInjector
import com.keepit.model.User
import org.specs2.mutable.Specification

class UserRecoFeedbackCounterTest extends Specification with CuratorTestInjector {
  "UserRecoFeedbackCounter" should {
    "update correctly" in {
      var counter = UserRecoFeedbackCounter.empty(Id[User](1), 16)

      counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.CLICKED)
      counter.voteUps.get(0) === 1

      counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.KEPT)
      counter.voteUps.get(0) === 6

      counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.DISLIKE)
      counter.voteDowns.get(0) === 5
    }

    "rescale correctly" in {
      var counter = UserRecoFeedbackCounter.empty(Id[User](1), 16)
      (0 until 51).foreach { i => counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.KEPT) }
      counter.voteUps.get(0) === 255
      (0 until 2).foreach { i => counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.DISLIKE) }
      counter.voteDowns.get(0) === 10

      counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.KEPT) // rescaled
      counter.voteUps.get(0) === 255 / 2 + 5
      counter.voteDowns.get(0) === 5
    }
  }

  "UserRecoFeedbackCounterRepo" should {
    "work" in {
      withDb() { implicit injector =>
        val repo = inject[UserRecoFeedbackCounterRepo]
        var counter = UserRecoFeedbackCounter.empty(Id[User](1), 512)
        counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.CLICKED)
        counter = counter.updateWithFeedback(0, UriRecoFeedbackValue.DISLIKE)
        db.readWrite { implicit s => repo.save(counter) }
        counter = db.readOnlyMaster { implicit s => repo.getByUser(counter.userId) }.get
        counter.voteUps.get(0) === 1
        counter.voteDowns.get(0) === 5

        db.readWrite { implicit s => repo.save(counter.updateWithFeedback(0, UriRecoFeedbackValue.CLICKED)) }
        counter = db.readOnlyMaster { implicit s => repo.getByUser(counter.userId) }.get
        counter.voteUps.get(0) === 2
        counter.voteDowns.get(0) === 5
      }
    }
  }
}
