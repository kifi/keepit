package com.keepit.cortex.models.lda

import com.keepit.cortex.article.StoreBasedArticleProvider
import com.keepit.cortex.nlp.Stopwords
import org.specs2.mutable.Specification
import com.keepit.cortex.features.URIFeatureTestHelper
import com.keepit.cortex.core._
import com.keepit.search.InMemoryArticleStoreImpl
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI
import com.keepit.common.db.Id
import com.keepit.model.UrlHash
import com.keepit.cortex.CortexTestInjector
import com.keepit.cortex.dbmodel._
import com.keepit.model.NormalizedURIStates
import com.keepit.common.db.State
import com.keepit.search.Lang

class LDADbUpdaterTest extends Specification with CortexTestInjector with LDADbTestHelper {
  "lda db updater" should {
    "fetch jobs and persist feature" in {
      withDb() { implicit injector =>
        val num = 5
        val uris = setup(num)
        val uriRepo = inject[CortexURIRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val topicRepo = inject[URILDATopicRepo]

        db.readWrite { implicit s =>
          uris.map { CortexURI.fromURI(_) }.foreach { uriRepo.save(_) }
        }

        val updater = new LDADbUpdaterImpl(uriReps, db, uriRepo, topicRepo, commitRepo)
        updater.update

        db.readOnlyMaster { implicit s =>
          topicRepo.all.size === num
          commitRepo.getByModelAndVersion(StatModelName.LDA, uriRep.version.version).get.seq === 5L
          (0 until num).foreach { i =>
            val feat = topicRepo.getByURI(uris(i).id.get, version).get
            feat.firstTopic.get.index === i
            feat.secondTopic.get.index === i + 1
            feat.thirdTopic.get.index === i + 2
            feat.firstTopicScore.get === 5f / 15f
            feat.state === URILDATopicStates.ACTIVE
            feat.feature.get.value(i) === 5f / 15f
            feat.feature.get.value(i + 1) === 4f / 15f
          }
        }

        1 === 1
      }
    }

    "deactivate existing feature if scraped -> other states" in {
      withDb() { implicit injector =>
        val num = 2
        val uris = setup(num)
        val uriRepo = inject[CortexURIRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val topicRepo = inject[URILDATopicRepo]

        db.readWrite { implicit s =>
          uris.map { CortexURI.fromURI(_) }.foreach { uriRepo.save(_) }
        }

        val updater = new LDADbUpdaterImpl(uriReps, db, uriRepo, topicRepo, commitRepo)
        updater.update

        db.readOnlyMaster { implicit s =>
          topicRepo.all.size === num
          commitRepo.getByModelAndVersion(StatModelName.LDA, uriRep.version.version).get.seq === 2L
          topicRepo.getByURI(uris(1).id.get, version).get.state === URILDATopicStates.ACTIVE
        }

        db.readWrite { implicit s =>
          val uri = uriRepo.getByURIId(uris(1).id.get).get
          uriRepo.save(CortexURI(id = uri.id, uriId = uri.uriId, state = State[CortexURI]("active"), seq = SequenceNumber[CortexURI](3L)))
        }

        updater.update

        db.readOnlyMaster { implicit s =>
          topicRepo.all.size === num
          commitRepo.getByModelAndVersion(StatModelName.LDA, uriRep.version.version).get.seq === 3L
          topicRepo.getByURI(uris(1).id.get, version).get.state === URILDATopicStates.INACTIVE
        }

      }
    }

    "mark feature as not_applicable if necessary" in {
      withDb() { implicit injector =>
        val num = 2
        val uris = setup(num, Lang("zh"))
        val uriRepo = inject[CortexURIRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val topicRepo = inject[URILDATopicRepo]

        db.readWrite { implicit s =>
          uris.map { CortexURI.fromURI(_) }.foreach { uriRepo.save(_) }
        }

        val updater = new LDADbUpdaterImpl(uriReps, db, uriRepo, topicRepo, commitRepo)
        updater.update

        db.readOnlyMaster { implicit s =>
          topicRepo.all.size === num
          commitRepo.getByModelAndVersion(StatModelName.LDA, uriRep.version.version).get.seq === 2L
          topicRepo.getByURI(uris(1).id.get, version).get.state === URILDATopicStates.NOT_APPLICABLE
        }
      }
    }

    "ignore uris if they are not scraped and don't have a feature yet" in {
      withDb() { implicit injector =>
        val num = 2
        val uris = setup(num)
        val uriRepo = inject[CortexURIRepo]
        val commitRepo = inject[FeatureCommitInfoRepo]
        val topicRepo = inject[URILDATopicRepo]
        db.readWrite { implicit s =>
          uris.map { CortexURI.fromURI(_).copy(state = State[CortexURI]("active"), shouldHaveContent = false) }.foreach { uriRepo.save(_) }
        }

        val updater = new LDADbUpdaterImpl(uriReps, db, uriRepo, topicRepo, commitRepo)
        updater.update

        db.readOnlyMaster { implicit s =>
          topicRepo.all.size === 0
          commitRepo.getByModelAndVersion(StatModelName.LDA, uriRep.version.version).get.seq === 2L
        }
      }
    }

  }
}

trait LDADbTestHelper extends URIFeatureTestHelper {
  val dim = 20
  val mapper = (0 until 20).map { i =>
    val w = "word" + i
    val topic = new Array[Float](dim)
    topic(i) = 1f
    w -> topic
  }.toMap

  val lda = DenseLDA(dim, mapper)
  val version = ModelVersion[DenseLDA](1)
  val wordRep = LDAWordRepresenter(version, lda)
  val docRep = new LDADocRepresenter(wordRep, Stopwords(Set())) {
    override val minValidTerms = 1
  }
  val articleStore = new InMemoryArticleStoreImpl()
  val uriRep = LDAURIRepresenter(docRep, new StoreBasedArticleProvider(articleStore))
  val uriReps = MultiVersionedLDAURIRepresenter(uriRep)

  def makeURI(idx: Int, seq: Option[SequenceNumber[NormalizedURI]] = None, state: State[NormalizedURI] = NormalizedURIStates.ACTIVE) = {
    NormalizedURI(id = Some(Id[NormalizedURI](idx)), url = s"http://page${idx}.com", urlHash = UrlHash(s"page${idx}"), seq = seq.getOrElse(SequenceNumber[NormalizedURI](idx)), state = state, shouldHaveContent = true)
  }

  // 5 * word_i + 4* word_(i+1) + ... + 1 * word_(i+5)
  def makeContent(idx: Int): String = {
    var s = ""
    ((idx until idx + 5).map { _ % dim } zip Range(5, 0, -1)).map {
      case (i, cnt) =>
        s += ("word" + i + " ") * cnt
    }
    s
  }

  def setup(num: Int, lang: Lang = Lang("en")) = {
    (0 until num).map { i =>
      val uri = makeURI(i + 1)
      val a = mkArticle(uri.id.get, title = "no title", content = makeContent(i), contentLang = lang)
      articleStore.+=(uri.id.get, a)
      uri
    }
  }

}
