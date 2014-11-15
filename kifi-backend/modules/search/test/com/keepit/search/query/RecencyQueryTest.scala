package com.keepit.search.query

import java.util.Random

import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.document.{ NumericDocValuesField, Document }
import org.apache.lucene.index.{ SlowCompositeReaderWrapper, DirectoryReader, IndexWriter, IndexWriterConfig }
import org.apache.lucene.search.{ DocIdSetIterator, IndexSearcher, MatchAllDocsQuery }
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import org.specs2.mutable.Specification
import scala.collection.mutable.ArrayBuffer
import scala.math._

class RecencyQueryTest extends Specification {

  private[this] val config = new IndexWriterConfig(Version.LATEST, DefaultAnalyzer.defaultAnalyzer)

  private[this] var seen = Set.empty[Long]
  private[this] val rnd = new Random()
  private[this] val range = 10000000
  private[this] val recency = (0 until 100).map { i =>
    var r = rnd.nextInt(range).toLong
    while (seen.contains(r)) r = rnd.nextInt(range).toLong
    seen += r
    (i.toLong, r)
  }.toMap

  private[this] val ramDir = new RAMDirectory
  private[this] val indexReader = {
    val now = System.currentTimeMillis()
    val writer = new IndexWriter(ramDir, config)
    recency.foreach {
      case (i, r) =>
        val doc = new Document()
        doc.add(new NumericDocValuesField("id", i))
        doc.add(new NumericDocValuesField("createdAt", now - r))
        writer.addDocument(doc)
    }
    writer.commit()
    writer.close()

    DirectoryReader.open(ramDir)
  }

  private[this] val reader = SlowCompositeReaderWrapper.wrap(indexReader)
  private[this] val readerContextLeaves = reader.leaves()
  private[this] val readerContext = readerContextLeaves.get(0)

  "scores recent documents higher" in {
    val searcher = new IndexSearcher(reader)
    val query = new RecencyQuery(new MatchAllDocsQuery(), "createdAt", 1.0f, range / 2)
    val weight = searcher.createNormalizedWeight(query)
    val scorer = weight.scorer(readerContext, reader.getLiveDocs)

    val idDocValues = reader.getNumericDocValues("id")
    val buf = new ArrayBuffer[(Long, Float)]()
    buf.clear
    var doc = scorer.nextDoc()
    while (doc < DocIdSetIterator.NO_MORE_DOCS) {
      var hit = ((idDocValues.get(doc), scorer.score()))
      buf += hit
      doc = scorer.nextDoc()
    }
    buf.size === recency.size
    buf.sortBy(_._2 * -1).map(_._1) === recency.toSeq.sortBy(_._2).map(_._1)
  }

  "scores recent documents within [1.0f, 1.0f + boostStrength]" in {
    val boostStrength = 0.5f
    val searcher = new IndexSearcher(reader)
    val query = new RecencyQuery(new MatchAllDocsQuery(), "createdAt", boostStrength, range / 2)
    val weight = searcher.createNormalizedWeight(query)
    val scorer = weight.scorer(readerContext, reader.getLiveDocs)

    val idDocValues = reader.getNumericDocValues("id")
    val buf = new ArrayBuffer[(Long, Float)]()
    buf.clear
    var doc = scorer.nextDoc()
    var maxScore = Float.MinValue
    var minScore = Float.MaxValue
    while (doc < DocIdSetIterator.NO_MORE_DOCS) {
      val score = scorer.score()
      maxScore = max(maxScore, score)
      minScore = min(minScore, score)
      doc = scorer.nextDoc()
    }
    maxScore must beLessThanOrEqualTo(1.0f + boostStrength)
    minScore must beGreaterThanOrEqualTo(1.0f)
  }
}
