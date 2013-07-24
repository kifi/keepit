package com.keepit.search.index

import akka.actor.Actor
import com.keepit.common.logging.Logging
import com.keepit.search.query.StringHash64
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.DocsEnum
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermsEnum
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS

class IndexWarmer(fields: Seq[String]) extends Logging {

  val filter = new Array[Short](3000)

  private def hash(field: String, text: String) = {
    val hasher = new StringHash64(12345L)
    hasher.update(field)
    hasher.update(text)
    hasher.get
  }

  def addTerms(terms: Iterable[Term]): Unit = {
    // no synchronization necessary. if multiple threads write to the filter, they may overwrite each other, but it is OK.
    terms.foreach{ term =>
      if (fields.contains(term.field())) {
        val hashVal = hash(term.field(), term.text())
        val fingerPrint = (hashVal & 0xFFFF).toShort
        val key1 = (hashVal >>> 16) % filter.size
        val key2 = (hashVal >>> 32) % filter.size
        filter(key1.toInt) = fingerPrint
        filter(key2.toInt) = fingerPrint
      }
    }
  }

  private def mayContain(field: String, text: String): Boolean = {
    val hashVal = hash(field, text)
    val fingerPrint = (hashVal & 0xFFFF).toShort
    val key1 = (hashVal >>> 16) % filter.size
    val key2 = (hashVal >>> 32) % filter.size
    (filter(key1.toInt) == fingerPrint || filter(key2.toInt) == fingerPrint)
  }

  def warm(indexReader: AtomicReader): Unit = {
    val now = System.currentTimeMillis()
    var termsEnum: TermsEnum = null
    var docsEnum: DocsEnum = null
    fields.foreach{ field =>
      val terms = indexReader.terms(field)
      if (terms != null) {
        termsEnum = terms.iterator(termsEnum)
        var byteRef = termsEnum.next()
        while (byteRef != null) {
          if (mayContain(field, byteRef.utf8ToString)) {
            docsEnum = termsEnum.docs(indexReader.getLiveDocs(), docsEnum)
            var doc = docsEnum.nextDoc
            while (doc < NO_MORE_DOCS) {
              doc = docsEnum.nextDoc
            }
          }
          byteRef = termsEnum.next()
        }
      }
    }
    val elapsed = System.currentTimeMillis() - now
    log.info(s"warm up took ${elapsed}ms (numDocs = ${indexReader.numDocs()})")
  }
}

