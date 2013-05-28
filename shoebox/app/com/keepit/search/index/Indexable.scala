package com.keepit.search.index

import com.keepit.common.db.{Id,SequenceNumber}
import com.keepit.search.SemanticVectorBuilder
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRef
import java.io.IOException
import java.io.StringReader


object Indexable {
  val textFieldType: FieldType = {
    new FieldType(TextField.TYPE_NOT_STORED)
  }

  val textFieldTypeNoNorm: FieldType = {
    val ft = new FieldType(TextField.TYPE_NOT_STORED)
    ft.setOmitNorms(true)
    ft
  }

  val keywordFieldType: FieldType = {
    new FieldType(StringField.TYPE_NOT_STORED)
  }

  val dataPayloadFieldType: FieldType = {
    val ft = new FieldType(TextField.TYPE_NOT_STORED)
    ft.setOmitNorms(true)
    ft
  }

  val semanticVectorFieldType: FieldType = {
    val ft = new FieldType(TextField.TYPE_NOT_STORED)
    ft.setOmitNorms(true)
    ft
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
}
import org.apache.lucene.document.BinaryDocValuesField

trait Indexable[T] {
  import Indexable._

  val sequenceNumber: SequenceNumber
  val id: Id[T]
  val isDeleted: Boolean

  val idTerm = new Term(Indexer.idFieldName, id.toString)

  def buildDocument: Document = {
    val doc = new Document()
    doc.add(buildKeywordField(Indexer.idFieldName, idTerm.text()))
    doc.add(buildIdValueField(id))
    doc
  }

  protected def buildKeywordField(fieldName: String, keyword: String) = {
    new Field(fieldName, keyword, keywordFieldType)
  }

  protected def buildTextField(fieldName: String, fieldValue: String) = {
    new Field(fieldName, fieldValue, textFieldType)
  }

  protected def buildTextField(fieldName: String, fieldValue: String, analyzer: Analyzer) = {
    val ts = analyzer.createLazyTokenStream(fieldName, fieldValue)
    new Field(fieldName, ts, textFieldType)
  }

  def buildIdValueField(typedId: Id[T]) = new NumericDocValuesField(Indexer.idValueFieldName, typedId.id)

  def buildDataPayloadField(term: Term, data: Array[Byte]): Field = {
    new Field(term.field(), new DataPayloadTokenStream(term.text(), data), dataPayloadFieldType)
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
          payloadAttr.setPayload(new BytesRef(data))
          termAttr.append(termText)
          posIncrAttr.setPositionIncrement(1)
          returnToken = false
          true
        case false => false
      }
    }
  }

  def buildIteratorField[A](fieldName: String, iterator: Iterator[A], fieldType: FieldType = textFieldTypeNoNorm)(toToken: (A=>String)) = {
    new Field(fieldName, new IteratorTokenStream(iterator, toToken), fieldType)
  }

  def buildDocSemanticVectorField(fieldName: String, svBuilder: SemanticVectorBuilder) = {
    new BinaryDocValuesField(fieldName, new BytesRef(svBuilder.aggregatedVector.bytes))
  }

  def buildSemanticVectorField(fieldName: String, svBuilder: SemanticVectorBuilder) = {
    new Field(fieldName, svBuilder.tokenStream, semanticVectorFieldType)
  }

}

