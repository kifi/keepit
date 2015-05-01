package com.keepit.cortex.models.lda

import com.keepit.cortex.article.StoreBasedArticleProvider
import com.keepit.cortex.nlp.Stopwords
import org.specs2.mutable.Specification
import com.keepit.cortex.features.WordFeatureTestHelper
import com.keepit.cortex.core._
import com.keepit.cortex.store._
import com.keepit.search.InMemoryArticleStoreImpl
import com.keepit.model.NormalizedURI
import com.keepit.model.UrlHash
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.cortex.plugins.URIPuller
import com.keepit.cortex.plugins.DataPuller
import com.keepit.cortex.features.URIFeatureTestHelper

trait LDATestHelper extends WordFeatureTestHelper with URIFeatureTestHelper {
  val dim = 2
  val lda = DenseLDA(dim, mapper)
  val version = ModelVersion[DenseLDA](1)

  val ldaModelStore = new InMemoryStatModelStore[DenseLDA] {
    val formatter = DenseLDAFormatter
  }

  ldaModelStore.+=(version, lda)

  val ldaFromStore = ldaModelStore.syncGet(version).get
  val articleStore = new InMemoryArticleStoreImpl()

  val wordRep = LDAWordRepresenter(version, ldaFromStore)
  val docRep = new LDADocRepresenter(wordRep, Stopwords(Set())) {
    override val minValidTerms = 1
  }
  val uriRep = LDAURIRepresenter(docRep, new StoreBasedArticleProvider(articleStore))

  val uri1 = NormalizedURI(id = Some(Id[NormalizedURI](1)), url = "http://intel.com", urlHash = UrlHash("intel"), seq = SequenceNumber[NormalizedURI](1))
  val uri2 = NormalizedURI(id = Some(Id[NormalizedURI](2)), url = "http://amd.com", urlHash = UrlHash("amd"), seq = SequenceNumber[NormalizedURI](2))
  val uri3 = NormalizedURI(id = Some(Id[NormalizedURI](3)), url = "http://fruit.com", urlHash = UrlHash("fruit"), seq = SequenceNumber[NormalizedURI](3))

  val a1 = mkArticle(uri1.id.get, title = "intel", content = "intel and amd are bros")
  val a2 = mkArticle(uri2.id.get, title = "amd", content = "amd rocks")
  val a3 = mkArticle(uri2.id.get, title = "fruit", content = "strawberry kiwi orange banana are great fruits")

  articleStore.+=(uri1.id.get, a1)
  articleStore.+=(uri2.id.get, a2)
  articleStore.+=(uri3.id.get, a3)

  class FakeURIPuller(allURI: Seq[NormalizedURI]) extends URIPuller {
    def getSince(lowSeq: SequenceNumber[NormalizedURI], limit: Int): Seq[NormalizedURI] = allURI.filter(_.seq > lowSeq).take(limit)
    def getBetween(lowSeq: SequenceNumber[NormalizedURI], highSeq: SequenceNumber[NormalizedURI]): Seq[NormalizedURI] = ???
  }

  val puller = new FakeURIPuller(Seq(uri1, uri2, uri3))

}
