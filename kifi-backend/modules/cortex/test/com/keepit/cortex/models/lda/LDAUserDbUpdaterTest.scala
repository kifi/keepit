package com.keepit.cortex.models.lda

import com.keepit.common.db.{ State, SequenceNumber, Id }
import com.keepit.common.time._
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.dbmodel._
import com.keepit.curator.FakeCuratorServiceClientImpl
import com.keepit.model.{ KeepSource, User, Keep }
import com.keepit.search.Lang
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.cortex.core.StatModelName

class LDAUserDbUpdaterTest extends Specification with CortexTestInjector with LDADbTestHelper {
  "lda user updater" should {
    "work" in {
      withDb() { implicit injector =>

        val num = 5
        val uris = setup(num)
        val uriRepo = inject[CortexURIRepo]
        val keepRepo = inject[CortexKeepRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val uriTopicRepo = inject[URILDATopicRepo]
        val userTopicRepo = inject[UserLDAInterestsRepo]
        val time = new DateTime(2014, 1, 30, 17, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        db.readWrite { implicit s =>
          uris.map { CortexURI.fromURI(_) } foreach { uriRepo.save(_) }
          val keeps = uris.map { uri => CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](1), uriId = uri.id.get, isPrivate = true, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](uri.seq.value), source = KeepSource.keeper) }
          keeps.foreach { keepRepo.save(_) }
        }

        val uriUpdater = new LDADbUpdaterImpl(uriReps, db, uriRepo, uriTopicRepo, commitRepo)
        val userTopicUpdater = new LDAUserDbUpdaterImpl(uriReps, db, keepRepo, uriTopicRepo, userTopicRepo, commitRepo, new FakeCuratorServiceClientImpl(null))

        uriUpdater.update()
        userTopicUpdater.update()

        db.readOnlyReplica { implicit s =>
          val model = userTopicRepo.getByUser(Id[User](1), uriRep.version).get
          model.numOfEvidence === 5
          val arr = model.userTopicMean.get.mean
          arr.take(5).toList === Array.fill(5)(1f / 5).toList
          arr.drop(5).toList === Array.fill(uriRep.dimension - 5)(0f).toList

          commitRepo.getByModelAndVersion(StatModelName.LDA_USER, 1).get.seq === 5
        }

      }

      "mark feature as not_applicable when appropriate" in {
        withDb() { implicit injector =>
          val num = 5
          val uris = setup(num, Lang("zh"))
          val uriRepo = inject[CortexURIRepo]
          val keepRepo = inject[CortexKeepRepo]
          val commitRepo = inject[FeatureCommitInfoRepo]
          val uriTopicRepo = inject[URILDATopicRepo]
          val userTopicRepo = inject[UserLDAInterestsRepo]
          val time = new DateTime(2014, 1, 30, 17, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

          db.readWrite { implicit s =>
            uris.map { CortexURI.fromURI(_) } foreach { uriRepo.save(_) }
            val keeps = uris.map { uri => CortexKeep(keptAt = time, userId = Id[User](1), keepId = Id[Keep](1), uriId = uri.id.get, isPrivate = true, state = State[CortexKeep]("active"), seq = SequenceNumber[CortexKeep](uri.seq.value), source = KeepSource.keeper) }
            keeps.foreach { keepRepo.save(_) }
          }

          val uriUpdater = new LDADbUpdaterImpl(uriReps, db, uriRepo, uriTopicRepo, commitRepo)
          val userTopicUpdater = new LDAUserDbUpdaterImpl(uriReps, db, keepRepo, uriTopicRepo, userTopicRepo, commitRepo, new FakeCuratorServiceClientImpl(null))

          uriUpdater.update()
          userTopicUpdater.update()

          db.readOnlyReplica { implicit s =>
            userTopicRepo.getTopicMeanByUser(Id[User](1), uriRep.version) === None
            userTopicRepo.getByUser(Id[User](1), uriRep.version).get.state === UserLDAInterestsStates.NOT_APPLICABLE
            commitRepo.getByModelAndVersion(StatModelName.LDA_USER, 1).get.seq === 5
            val model = userTopicRepo.getByUser(Id[User](1), uriRep.version).get
            model.numOfEvidence === 0
          }
        }
      }
    }
  }
}
