package com.keepit.curator

import java.util.concurrent.atomic.AtomicInteger

import com.keepit.cortex.models.lda.LDATopic
import com.keepit.curator.commanders.{ UriRecoScore, DiverseRecoSortStrategy }
import org.specs2.mutable.Specification

class DiverseRecoSortStrategyTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  "DiverseRecoSortStrategy" should {
    "sorts recommendations to include multiple topics" in {
      withInjector() { implicit injector =>
        // test is more of a sanity check to make sure (1) recos are sorted correctly and
        // (2) some weight is applied to recos by the topics they are in
        val topicScores = Map[Int, Seq[Float]](
          1 -> Seq(8, 8, 9, 10),
          2 -> Seq(8, 9, 10),
          3 -> Seq(8, 9, 10),
          4 -> Seq(7.9f, 8.9f)
        )

        val nextId = new AtomicInteger(1)
        val recos = (for (topic <- topicScores.keys; score <- topicScores(topic)) yield makeUriRecommendation(
          nextId.getAndIncrement, 42, score).copy(topic1 = Some(LDATopic(topic)))).toSeq

        val sortedRecos = recos.sortBy(-_.masterScore) map { reco => UriRecoScore(reco.masterScore, reco) }

        val strategy = new DiverseRecoSortStrategy()
        val diverseSortedRecos = strategy.sort(sortedRecos)

        // sort order sanity check
        for (i <- 1 until diverseSortedRecos.size) {
          diverseSortedRecos(i - i).score > diverseSortedRecos(i).score
        }

        // ensures that the 8.9f and 7.9f scores are ranked above the other scores of 9 and 8, respectively
        // test is susceptible to fail from algorithm changes or changes to the topicScores Map above
        diverseSortedRecos(3).reco.masterScore === 8.9f
        diverseSortedRecos(4).reco.masterScore === 9
        diverseSortedRecos(7).reco.masterScore === 7.9f
        diverseSortedRecos(8).reco.masterScore === 8
      }
    }
  }

}
