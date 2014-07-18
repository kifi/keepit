package com.keepit.common.crypto

import javax.crypto.spec.IvParameterSpec

import com.keepit.common.db.Id
import org.specs2.mutable._

class PublicIdTest extends Specification {

  "PublicId" should {
    implicit val config = PublicIdConfiguration("secret key")

    "encode and decode public ids" in {
      case class TestModel(id: Option[Id[TestModel]]) extends ModelWithPublicId[TestModel]
      object TestModel extends ModelWithPublicIdCompanion[TestModel] {
        protected[this] val publicIdPrefix = "t"
        protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-72, -49, 51, -61, 42, 43, 123, -61, 64, 122, -121, -55, 117, -51, 12, 21))
      }

      case class TestModel2(id: Option[Id[TestModel2]]) extends ModelWithPublicId[TestModel2]
      object TestModel2 extends ModelWithPublicIdCompanion[TestModel2] {
        protected[this] val publicIdPrefix = "w"
        protected[this] val publicIdIvSpec = new IvParameterSpec(Array(0, 0, 0, 0, -10, 100, 100, 10, 42, 42, 42, 42, 42, 42, 42, 42))
      }

      val id1 = Id[TestModel](1L)
      val id2 = Id[TestModel](2L)
      val id3 = Id[TestModel2](1L)

      val pid1 = TestModel.publicId(id1)
      val pid2 = TestModel.publicId(id2)
      val pid3 = TestModel2.publicId(id3)

      // PublicIds should be different
      pid1 !== pid2
      pid1 !== pid3
      pid2 !== pid3

      // PublicIds from the same Id value but for different types should be different
      id1.id === id3.id
      pid1.id.zip(pid3.id).count(c => c._1 != c._2) must be_>(5)

      // Values are correct (sensitive to changes to IV and key)
      pid1.id === "tKhpz2uJdFZB"
      pid2.id === "tKhpz2uJdFY8"  // TODO: this should be much more different from pid1.id
      pid3.id === "w9wcfGVcIpAT"

      // Encryption is consistant
      TestModel.publicId(Id[TestModel](1)) === pid1

      // Decryption works
      TestModel.decodePublicId(pid1).get === id1

      // Decryption with the wrong key should fail
      TestModel.decodePublicId(pid1)(PublicIdConfiguration("otherkey")).get must throwAn[IllegalArgumentException]
    }
  }
}
