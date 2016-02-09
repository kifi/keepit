package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.index.IndexDirectory
import com.keepit.search.index.Indexer
import com.keepit.search.index.Indexable
import org.apache.lucene.analysis.Analyzer
import java.io.StringReader

class Tst(val id: Id[Tst], val text: String, val personalText: String)

class TstIndexer(indexDirectory: IndexDirectory) extends Indexer[Tst, Tst, TstIndexer](indexDirectory) {
  val name = "TestIndexer"
  def buildIndexable(id: Id[Tst]): Indexable[Tst, Tst] = throw new UnsupportedOperationException()
  def buildIndexable(data: Tst): Indexable[Tst, Tst] = new TstIndexable(data.id, data.text, data.personalText, indexWriterConfig.getAnalyzer)

  def index(id: Id[Tst], text: String, personalText: String) = {
    indexDocuments(Some(buildIndexable(new Tst(id, text, personalText))).iterator, 100)
  }

  override val airbrake: AirbrakeNotifier = null
  def update(): Int = ???
}

class TstIndexable(override val id: Id[Tst], val text: String, val personalText: String, analyzer: Analyzer) extends Indexable[Tst, Tst] {

  implicit def toReader(text: String) = new StringReader(text)

  override val sequenceNumber = SequenceNumber.ZERO[Tst]
  override val isDeleted = false

  override def buildDocument = {
    val doc = super.buildDocument
    val content = buildTextField("c", text)
    val personal = buildTextField("p", personalText)
    doc.add(content)
    doc.add(personal)
    doc
  }
}

