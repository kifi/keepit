package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.SemanticVectorBuilder
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.Payload
import org.apache.lucene.index.Term
import java.io.IOException
import java.io.StringReader

trait Indexable[T] {

  val sequenceNumber: SequenceNumber
  val id: Id[T]
  val isDeleted: Boolean

  val idTerm = Indexer.idFieldTerm.createTerm(id.toString)

  def buildDocument: Document = {
    val doc = new Document()
    doc.add(buildKeywordField(Indexer.idFieldName, idTerm.text()))
    doc.add(buildIdPayloadField(id))
    doc
  }

  protected def buildKeywordField(fieldName: String, keyword: String) = {
    new Field(fieldName, keyword, Field.Store.NO, Field.Index.NOT_ANALYZED_NO_NORMS, Field.TermVector.NO)
  }

  protected def buildTextField(fieldName: String, fieldValue: String) = {
    new Field(fieldName, fieldValue, Field.Store.NO, Field.Index.ANALYZED, Field.TermVector.NO)
  }

  protected def buildTextField(fieldName: String, fieldValue: String, analyzer: Analyzer) = {
    new Field(fieldName, analyzer.tokenStream(fieldName, new StringReader(fieldValue)))
  }

  def buildIdPayloadField(typedId: Id[T]) = {
    val id = typedId.id
    val buf = new Array[Byte](8)
    buf(0) = id.toByte
    buf(1) = (id >> 8).toByte
    buf(2) = (id >> 16).toByte
    buf(3) = (id >> 24).toByte
    buf(4) = (id >> 32).toByte
    buf(5) = (id >> 40).toByte
    buf(6) = (id >> 48).toByte
    buf(7) = (id >> 56).toByte
    buildDataPayloadField(Indexer.idPayloadTerm, buf)
  }

  def buildDataPayloadField(term: Term, data: Array[Byte]): Field = {
    val fld = new Field(term.field(), new DataPayloadTokenStream(term.text(), data))
    fld.setOmitNorms(true)
    fld
  }

  class DataPayloadTokenStream(termText: String, data: Array[Byte]) extends TokenStream {
    val termAttr = addAttribute(classOf[CharTermAttribute])
    val payloadAttr = addAttribute(classOf[PayloadAttribute])
    val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute]);
    var returnToken = true;

    @throws(classOf[IOException])
    override def incrementToken(): Boolean = {
      returnToken match {
        case true =>
          payloadAttr.setPayload(new Payload(data))
          termAttr.append(termText)
          posIncrAttr.setPositionIncrement(1)
          returnToken = false
          true
        case false => false
      }
    }
  }

  def buildIteratorField[A](fieldName: String, iterator: Iterator[A])(toToken: (A=>String)) = {
    new Field(fieldName, new IteratorTokenStream(iterator, toToken))
  }

  class IteratorTokenStream[A](iterator: Iterator[A], toToken: (A=>String)) extends TokenStream {
    val termAttr = addAttribute(classOf[CharTermAttribute])
    val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute]);

    @throws(classOf[IOException])
    override def incrementToken(): Boolean = {
      clearAttributes()
      iterator.hasNext match {
        case true =>
          val termText = toToken(iterator.next)
          termAttr.append(termText)
          posIncrAttr.setPositionIncrement(1)
          true
        case false => false
      }
    }
  }

  def buildSemanticVectorField(fieldName: String, tokenStreams: TokenStream*) = {
    val builder = new SemanticVectorBuilder(60)
    tokenStreams.foreach{ builder.load(_) }
    new Field(fieldName, builder.tokenStream)
  }
}

