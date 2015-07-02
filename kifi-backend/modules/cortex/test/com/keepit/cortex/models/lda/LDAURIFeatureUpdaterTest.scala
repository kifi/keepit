package com.keepit.cortex.models.lda

import com.keepit.cortex.article.{ FakeCortexArticleProvider }
import com.keepit.cortex.nlp.Stopwords
import com.keepit.cortex.features.WordFeatureTestHelper
import com.keepit.cortex.core._
import com.keepit.cortex.store._
import com.keepit.model.NormalizedURI
import com.keepit.model.UrlHash
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.plugins.URIPuller

trait LDATestHelper extends WordFeatureTestHelper {
  val dim = 2
  val lda = DenseLDA(dim, mapper)
  val version = ModelVersion[DenseLDA](1)

  val ldaModelStore = new InMemoryStatModelStore[DenseLDA] {
    val formatter = DenseLDAFormatter
  }

  ldaModelStore.+=(version, lda)

  val ldaFromStore = ldaModelStore.syncGet(version).get

  val wordRep = LDAWordRepresenter(version, ldaFromStore)
  val docRep = new LDADocRepresenter(wordRep, Stopwords(Set())) {
    override val minValidTerms = 1
  }
  val articleProvider = new FakeCortexArticleProvider()
  val uriRep = LDAURIRepresenter(docRep, articleProvider)

  val uri1 = NormalizedURI(id = Some(Id[NormalizedURI](1)), url = "http://intel.com", urlHash = UrlHash("intel"), seq = SequenceNumber[NormalizedURI](1))
  val uri2 = NormalizedURI(id = Some(Id[NormalizedURI](2)), url = "http://amd.com", urlHash = UrlHash("amd"), seq = SequenceNumber[NormalizedURI](2))
  val uri3 = NormalizedURI(id = Some(Id[NormalizedURI](3)), url = "http://fruit.com", urlHash = UrlHash("fruit"), seq = SequenceNumber[NormalizedURI](3))

  articleProvider.setArticle(uri1.id.get, "intel and amd are bros")
  articleProvider.setArticle(uri2.id.get, "amd rocks")
  articleProvider.setArticle(uri3.id.get, "strawberry kiwi orange banana are great fruits")

  class FakeURIPuller(allURI: Seq[NormalizedURI]) extends URIPuller {
    def getSince(lowSeq: SequenceNumber[NormalizedURI], limit: Int): Seq[NormalizedURI] = allURI.filter(_.seq > lowSeq).take(limit)
    def getBetween(lowSeq: SequenceNumber[NormalizedURI], highSeq: SequenceNumber[NormalizedURI]): Seq[NormalizedURI] = ???
  }

  val puller = new FakeURIPuller(Seq(uri1, uri2, uri3))

}
