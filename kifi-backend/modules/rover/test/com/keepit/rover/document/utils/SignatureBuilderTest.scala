package com.keepit.rover.document.utils

import org.specs2.mutable._

class SignatureBuilderTest extends Specification {

  "SignatureBuilder" should {

    "estimate similarity" in {
      val builder1 = new SignatureBuilder(3)
      val builder2 = new SignatureBuilder(3)
      val sig1 = builder1.add("aaa bbb ccc ddd eee  fff ggg hhh iii jjj  kkk lll mmm nnn ooo  ppp qqq rrr sss ttt").build
      val sig2 = builder2.add("aaa bbb ccc ddd eee  fff ggg hhh iii x  kkk lll mmm nnn ooo  ppp qqq rrr sss ttt").build
      val sig3 = builder1.add("uuu").build

      val sim11 = sig1 similarTo sig1
      val sim22 = sig2 similarTo sig2
      val sim33 = sig3 similarTo sig3

      sim11 === 1.0d
      sim22 === 1.0d
      sim33 === 1.0d

      val sim12 = sig1 similarTo sig2
      val sim13 = sig1 similarTo sig3
      val sim23 = sig2 similarTo sig3
      val sim21 = sig2 similarTo sig1
      val sim31 = sig3 similarTo sig1
      val sim32 = sig3 similarTo sig2

      // symmetric similarity score
      sim12 === sim21
      sim13 === sim31
      sim23 === sim32

      (sim12 < sim11) === true
      (sim12 < sim13) === true
    }

  }

}
