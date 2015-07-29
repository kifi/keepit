package com.keepit.search.index.graph.library

import com.keepit.common.db.Id
import com.keepit.model._
import com.keepit.search.index._
import com.keepit.search.LangDetector
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term

object LibraryFields {
  val nameField = "t"
  val nameStemmedField = "ts"
  val namePrefixField = "tp"
  val nameValueField = "tv"
  val descriptionField = "c"
  val descriptionStemmedField = "cs"
  val visibilityField = "v"
  val kindField = "k"
  val ownerField = "o"
  val ownerIdField = "oid"
  val orgField = "org"
  val orgIdField = "orgId"
  val recordField = "rec"
  val keepCountValueField = "kc"

  val minimalSearchFields = Set(nameField, nameStemmedField)
  val fullTextSearchFields = Set(descriptionField, descriptionStemmedField)
  val prefixSearchFields = Set(namePrefixField)

  val maxPrefixLength = 8

  object Visibility {
    val SECRET = 0
    val DISCOVERABLE = 1
    val PUBLISHED = 2
    val ORGANIZATION = 3

    @inline def toNumericCode(visibility: LibraryVisibility) = visibility match {
      case LibraryVisibility.SECRET => SECRET
      case LibraryVisibility.DISCOVERABLE => DISCOVERABLE
      case LibraryVisibility.PUBLISHED => PUBLISHED
      case LibraryVisibility.ORGANIZATION => ORGANIZATION
    }

    @inline def fromNumericCode(visibility: Long) = visibility match {
      case SECRET => LibraryVisibility.SECRET
      case DISCOVERABLE => LibraryVisibility.DISCOVERABLE
      case ORGANIZATION => LibraryVisibility.ORGANIZATION
      case _ => LibraryVisibility.PUBLISHED
    }
  }

  object Kind {
    val SYSTEM_MAIN = 0
    val SYSTEM_SECRET = 1
    val USER_CREATED = 2
    val SYSTEM_PERSONA = 3
    val SYSTEM_READ_IT_LATER = 4

    @inline def toNumericCode(kind: LibraryKind) = kind match {
      case LibraryKind.SYSTEM_MAIN => SYSTEM_MAIN
      case LibraryKind.SYSTEM_SECRET => SYSTEM_SECRET
      case LibraryKind.USER_CREATED => USER_CREATED
      case LibraryKind.SYSTEM_PERSONA => SYSTEM_PERSONA
      case LibraryKind.SYSTEM_READ_IT_LATER => SYSTEM_READ_IT_LATER
    }

    @inline def fromNumericCode(kind: Long) = {
      if (kind == SYSTEM_MAIN) LibraryKind.SYSTEM_MAIN
      else if (kind == SYSTEM_SECRET) LibraryKind.SYSTEM_SECRET
      else if (kind == SYSTEM_READ_IT_LATER) LibraryKind.SYSTEM_READ_IT_LATER
      else LibraryKind.USER_CREATED
    }
  }

  val decoders: Map[String, FieldDecoder] = Map(
    nameValueField -> DocUtil.stringDocValFieldDecoder,
    recordField -> DocUtil.binaryDocValFieldDecoder(LibraryRecord.fromByteArray(_, _, _).toString)
  )
}

object LibraryIndexable {
  def isSecret(librarySearcher: Searcher, libraryId: Id[Library]): Boolean = {
    getVisibility(librarySearcher, libraryId.id).exists(_ == LibraryVisibility.SECRET)
  }

  def isPublished(librarySearcher: Searcher, libId: Long): Boolean = {
    getVisibility(librarySearcher, libId).exists(_ == LibraryVisibility.PUBLISHED)
  }

  def getVisibility(librarySearcher: Searcher, libId: Long): Option[LibraryVisibility] = {
    librarySearcher.getLongDocValue(LibraryFields.visibilityField, libId).map(LibraryFields.Visibility.fromNumericCode)
  }

  def getKind(librarySearcher: Searcher, libId: Long): Option[LibraryKind] = {
    librarySearcher.getLongDocValue(LibraryFields.kindField, libId).map(LibraryFields.Kind.fromNumericCode)
  }

  def getName(librarySearcher: Searcher, libId: Long): Option[String] = {
    librarySearcher.getStringDocValue(LibraryFields.nameValueField, libId)
  }

  def getRecord(librarySearcher: Searcher, libraryId: Id[Library]): Option[LibraryRecord] = {
    librarySearcher.getDecodedDocValue(LibraryFields.recordField, libraryId.id)
  }

  def getKeepCount(librarySearcher: Searcher, libId: Long): Option[Long] = {
    librarySearcher.getLongDocValue(LibraryFields.keepCountValueField, libId)
  }
}

class LibraryIndexable(library: DetailedLibraryView) extends Indexable[Library, Library] {

  val id = library.id.get
  val sequenceNumber = library.seq
  val isDeleted: Boolean = (library.state == LibraryStates.INACTIVE)

  override def buildDocument = {
    import LibraryFields._
    val doc = super.buildDocument

    val nameLang = LangDetector.detect(library.name)
    doc.add(buildTextField(nameField, library.name, DefaultAnalyzer.getAnalyzer(nameLang)))
    doc.add(buildTextField(nameStemmedField, library.name, DefaultAnalyzer.getAnalyzerWithStemmer(nameLang)))
    doc.add(buildPrefixField(namePrefixField, library.name, maxPrefixLength))
    doc.add(buildStringDocValuesField(nameValueField, library.name))

    library.description.foreach { description =>
      val descriptionLang = LangDetector.detect(description)
      doc.add(buildTextField(descriptionField, description, DefaultAnalyzer.getAnalyzer(descriptionLang)))
      doc.add(buildTextField(descriptionStemmedField, description, DefaultAnalyzer.getAnalyzerWithStemmer(descriptionLang)))
    }

    doc.add(buildKeywordField(ownerField, library.ownerId.id.toString))
    library.orgId.foreach { orgId =>
      doc.add(buildKeywordField(orgField, orgId.id.toString))
    }

    doc.add(buildIdValueField(ownerIdField, library.ownerId))
    doc.add(buildLongValueField(visibilityField, Visibility.toNumericCode(library.visibility)))
    doc.add(buildLongValueField(kindField, Kind.toNumericCode(library.kind)))

    doc.add(buildIdValueField(orgIdField, library.orgId.getOrElse(Id[Organization](-1))))

    doc.add(buildBinaryDocValuesField(recordField, LibraryRecord(library)))

    doc.add(buildLongValueField(keepCountValueField, library.keepCount))

    doc
  }
}
