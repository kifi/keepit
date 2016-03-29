package com.keepit.eliza.model

import com.keepit.common.util.Ord
import org.specs2.mutable.Specification
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.libs.json.JsNull
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._
import com.keepit.test.ElizaTestInjector
import org.joda.time.{ DateTime, Days }

import scala.util.Random

class MessageRepoTest extends Specification with ElizaTestInjector {
  val modules = Seq(
    ElizaCacheModule(),
    FakeShoeboxServiceModule()
  )

  "MessageRepo" should {
    def rand(lo: Int, hi: Int) = lo + Random.nextInt(hi - lo + 1)
    "grab recent messages grouped by keep" in {
      withDb(modules: _*) { implicit injector =>
        val now = inject[Clock].now
        db.readWrite { implicit s =>
          val keepIds = Random.shuffle(Seq.range(1, 1000).map(Id[Keep](_))).take(50)
          val threads = keepIds.map { keepId =>
            MessageThreadFactory.thread().withKeep(keepId).saved
          }
          val messagesByKeep = threads.map { thread =>
            thread.keepId -> MessageFactory.messages(50).map { msg =>
              msg.withThread(thread).withCreatedAt(now plusHours rand(-100, 100)).saved
            }.sortBy(x => (x.createdAt.getMillis, x.id.get.id))(Ord.descending).map(_.id.get)
          }.toMap

          (1 to 10).foreach { _ =>
            val randomKeeps = Random.shuffle(keepIds).take(10)
            val numMsgs = rand(5, 15)
            messageRepo.getRecentByKeeps(randomKeeps.toSet, beforeOpt = None, numMsgs).foreach {
              case (keepId, recentMsgIds) => recentMsgIds === messagesByKeep(keepId).take(numMsgs)
            }
            val before = now
            val recentMsgsBeforeX = messageRepo.getAll(messageRepo.getRecentByKeeps(randomKeeps.toSet, beforeOpt = Some(before), numMsgs).values.flatten.toSeq).values
            recentMsgsBeforeX.forall(_.createdAt < before) === true
          }
          1 === 1
        }
      }
    }
  }
}
