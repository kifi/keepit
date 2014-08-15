package com.keepit.model

import com.keepit.model.helprank.UserBookmarkClicksRepo
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.test._

class UserKeepInfoRepoTest extends Specification with HeimdalTestInjector {
  "userKeepInfoRepo" should {
    "keep counts" in {
      withDb() { implicit injector =>
        val N = 10
        val userIds = (1 to N).map { Id[User](_) }
        val uriIds = (1 to N).map { Id[NormalizedURI](_) }
        val repo = inject[UserBookmarkClicksRepo]

        val rekeepCounts = Array.fill[Int](N) { util.Random.nextInt(N) }
        val rekeepTotalCounts = rekeepCounts map { rk => rk + util.Random.nextInt(N / 2) }

        (userIds zip uriIds) foreach {
          case (userId, uriId) =>
            db.readWrite { implicit s =>
              val numSelf = userId.id.toInt
              val numOther = N - numSelf
              (0 until numSelf).foreach { i => repo.increaseCounts(userId, uriId, isSelf = true) }
              (0 until numOther).foreach { i => repo.increaseCounts(userId, uriId, isSelf = false) }

              repo.all.zipWithIndex map {
                case (row, i) =>
                  repo.save(row.copy(rekeepCount = rekeepCounts(i), rekeepTotalCount = rekeepTotalCounts(i)))
              }
            }
        }

        val all = db.readOnlyMaster { implicit s => repo.all }

        (userIds zip uriIds) foreach {
          case (userId, uriId) =>
            val rec = db.readOnlyMaster { implicit s =>
              repo.getByUserUri(userId, uriId).get
            }

            val numSelf = userId.id.toInt
            val numOther = N - numSelf
            rec.selfClicks === numSelf
            rec.otherClicks === numOther

            val (rekeepCount, rekeepTotalCount) = db.readOnlyMaster { implicit s =>
              repo.getReKeepCounts(userId)
            }
            val userRecords = all.filter(_.userId == userId)
            rekeepCount === userRecords.foldLeft(0) { (a, c) => a + c.rekeepCount }
            rekeepTotalCount === userRecords.foldLeft(0) { (a, c) => a + c.rekeepTotalCount }
        }
      }
    }
  }
}
